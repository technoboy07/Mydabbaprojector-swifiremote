package com.m2smartremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PolicyAndRoutingTest {

    @Test
    public void hostAndOriginMustMatchBoundAddress() {
        OriginPolicy p = new OriginPolicy("192.168.1.50", 8765);
        assertTrue(p.hostAllowed("192.168.1.50:8765"));
        assertFalse(p.hostAllowed("evil.example:8765"));      // DNS rebinding
        assertFalse(p.hostAllowed("192.168.1.50"));
        assertFalse(p.hostAllowed(null));
        assertTrue(p.originAllowed("http://192.168.1.50:8765", true));
        assertFalse(p.originAllowed("http://evil.example", true));
        assertFalse(p.originAllowed("https://192.168.1.50:8765", true));
        assertFalse(p.originAllowed("null", true));
        assertFalse(p.originAllowed(null, true));
        assertTrue(p.originAllowed(null, false));
        assertTrue(OriginPolicy.fetchSiteAllowed(null));
        assertTrue(OriginPolicy.fetchSiteAllowed("same-origin"));
        assertFalse(OriginPolicy.fetchSiteAllowed("cross-site"));
        assertFalse(OriginPolicy.fetchSiteAllowed("same-site"));
    }

    @Test
    public void onlyPrivateAddressesAreLocal() {
        assertTrue(OriginPolicy.isPrivateIpv4("192.168.0.4"));
        assertTrue(OriginPolicy.isPrivateIpv4("10.1.2.3"));
        assertTrue(OriginPolicy.isPrivateIpv4("172.16.0.1"));
        assertTrue(OriginPolicy.isPrivateIpv4("172.31.255.255"));
        assertTrue(OriginPolicy.isPrivateIpv4("::ffff:192.168.0.4"));
        assertFalse(OriginPolicy.isPrivateIpv4("172.32.0.1"));
        assertFalse(OriginPolicy.isPrivateIpv4("8.8.8.8"));
        assertFalse(OriginPolicy.isPrivateIpv4("192.168.0"));
        assertFalse(OriginPolicy.isPrivateIpv4("192.168.0.256"));
        assertFalse(OriginPolicy.isPrivateIpv4("fe80::1"));
        assertFalse(OriginPolicy.isPrivateIpv4(null));
    }

    @Test
    public void commandAllowlistIsExact() {
        assertEquals(Command.UP, Command.parse("UP"));
        assertNull(Command.parse("up"));
        assertNull(Command.parse("SHELL"));
        assertNull(Command.parse("UP; reboot"));
        assertNull(Command.parse(""));
        assertNull(Command.parse(null));
        assertNull(PointerAction.parse("move"));
        assertEquals(PointerAction.MOVE, PointerAction.parse("MOVE"));
    }

    @Test
    public void textIsBoundedAndStripped() {
        assertEquals("hello world", TextInput.sanitize("hello\n world\u0000"));
        assertNull(TextInput.sanitize(new String(new char[TextInput.MAX_LENGTH + 1]).replace('\0', 'a')));
        assertNull(TextInput.sanitize(null));
    }

    // ---- router with fakes -----------------------------------------------------------------

    static final class FakeInput implements InputBackend {
        boolean available = true;
        boolean gestures = true;
        final List<String> calls = new ArrayList<>();

        public boolean isAvailable() { return available; }
        public boolean canDispatchGestures() { return gestures; }
        public ActionResult navigate(Direction d) { calls.add("nav:" + d); return ActionResult.ok(); }
        public ActionResult select() { calls.add("select"); return ActionResult.ok(); }
        public ActionResult longPressFocused() { calls.add("long"); return ActionResult.ok(); }
        public ActionResult globalAction(Command c) { calls.add("global:" + c); return ActionResult.ok(); }
        public ActionResult pointer(PointerAction a, int dx, int dy) { calls.add("ptr:" + a + ":" + dx + "," + dy); return ActionResult.ok(); }
        public ActionResult setText(String t) { calls.add("text:" + t); return ActionResult.ok(); }
    }

    static final class FakeAudio implements AudioBackend {
        final List<String> calls = new ArrayList<>();

        public ActionResult volume(Command c) { calls.add(c.name()); return ActionResult.ok(); }
        public ActionResult mediaKey(Command c) { calls.add(c.name()); return ActionResult.ok(); }
    }

    @Test
    public void everyCommandRoutesSomewhere() {
        FakeInput in = new FakeInput();
        FakeAudio au = new FakeAudio();
        CommandRouter r = new CommandRouter(in, au);
        for (Command c : Command.values()) {
            assertTrue(c.name(), r.command(c).isOk());
        }
        assertEquals(Command.values().length, in.calls.size() + au.calls.size());
        assertTrue(in.calls.contains("nav:UP"));
        assertTrue(in.calls.contains("long"));
        assertTrue(in.calls.contains("global:HOME"));
        assertTrue(au.calls.contains("MEDIA_PLAY_PAUSE"));
    }

    @Test
    public void accessibilityDisabledBlocksNavigationButNotAudio() {
        FakeInput in = new FakeInput();
        in.available = false;
        FakeAudio au = new FakeAudio();
        CommandRouter r = new CommandRouter(in, au);
        assertEquals(ApiError.ACCESSIBILITY_DISABLED, r.command(Command.UP).error);
        assertEquals(ApiError.ACCESSIBILITY_DISABLED, r.command(Command.BACK).error);
        assertEquals(ApiError.ACCESSIBILITY_DISABLED, r.pointer(PointerAction.MOVE, 1, 1).error);
        assertEquals(ApiError.ACCESSIBILITY_DISABLED, r.text("x").error);
        assertTrue(r.command(Command.VOLUME_UP).isOk());
        assertTrue(r.command(Command.MEDIA_NEXT).isOk());
        assertTrue(in.calls.isEmpty());
    }

    @Test
    public void nullCommandIsUnsupported() {
        CommandRouter r = new CommandRouter(new FakeInput(), new FakeAudio());
        assertEquals(ApiError.UNSUPPORTED_COMMAND, r.command(null).error);
        assertEquals(ApiError.UNSUPPORTED_COMMAND, r.pointer(null, 0, 0).error);
    }

    @Test
    public void pointerValidation() {
        FakeInput in = new FakeInput();
        CommandRouter r = new CommandRouter(in, new FakeAudio());
        assertEquals(ApiError.INVALID_INPUT, r.pointer(PointerAction.MOVE, 5000, 0).error);
        assertTrue(r.pointer(PointerAction.MOVE, 10, -4).isOk());
        in.gestures = false;
        assertTrue(r.pointer(PointerAction.MOVE, 1, 1).isOk());
        assertEquals(ApiError.ACTION_FAILED, r.pointer(PointerAction.TAP, 0, 0).error);
    }

    @Test
    public void overlongTextIsRejectedBeforeReachingInput() {
        FakeInput in = new FakeInput();
        CommandRouter r = new CommandRouter(in, new FakeAudio());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= TextInput.MAX_LENGTH; i++) {
            sb.append('x');
        }
        assertEquals(ApiError.INVALID_INPUT, r.text(sb.toString()).error);
        assertTrue(in.calls.isEmpty());
    }

    @Test
    public void rateLimiterRefills() {
        FakeClock clock = new FakeClock();
        RateLimiter rl = new RateLimiter(clock, 3, 1);
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("b"));
        clock.advance(1000);
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
    }
}
