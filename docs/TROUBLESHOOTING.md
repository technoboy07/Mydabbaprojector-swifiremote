# Troubleshooting

Useful commands (PC with ADB connected to the projector):

```bash
adb logcat -s M2Remote                              # app log; never contains codes or keys
adb shell ip -4 addr                                # projector addresses
adb shell dumpsys activity services com.m2smartremote   # is RemoteService running?
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | grep -i m2smartremote
```

From a laptop on the same Wi-Fi:

```bash
curl -i http://PROJECTOR_IP:8765/api/hello          # 200 + {"app":"M2 Smart Remote",...}
```

`/api/hello` requires the exact `Host` header, which curl sends correctly when you use the IP.

---

### The phone cannot reach the projector IP

- Check that both devices are on the same SSID. Guest networks and "client/AP isolation" block
  device-to-device traffic, so turn isolation off or use the main network.
- Mobile data or a VPN on the phone can hijack the route. Turn the VPN off and try again.
- Check the projector shows *Server: Running*. *Waiting for network* means it has no private IPv4
  address.
- Type `http://`, not `https://`. Chrome may try HTTPS first; type the full URL including the port.
- Run `curl` from a laptop. If that works but the phone doesn't, the problem is on the phone.

### The projector IP changed

The app re-binds automatically on network changes and checks every 30 s. Re-open the remote at the
new address shown on the projector. The pairing still works because sessions are not tied to the IP.
To avoid changes, add a DHCP reservation for the projector's MAC address in your router.

### "Port 8765 is already in use"

Another app is listening on it. In *Settings* on the projector, choose another port (1024–65535),
press **Save port**, and use the new address. To find the owner:
`adb shell netstat -tlnp | grep 8765` (works on most firmwares).

### The server stops when the app is in the background

The server runs in a foreground service (there is a "M2 Smart Remote is on" notification), which
Android keeps alive. If it still dies:

- Some vendor "memory cleaner" or "speed up" tools kill everything. Exclude the app or don't run them.
- After a reboot it only restarts if *Start on boot* is on **and** you didn't press Stop last time.
- `adb logcat -s M2Remote ActivityManager` shows whether it was killed.

### The accessibility service is disabled, or keeps turning off

- The phone shows an amber "Accessibility off" pill and banner. Re-enable it on the projector
  (*Enable accessibility service*).
- Android disables accessibility services when the app is force-stopped or updated with a different
  signature. Re-enable it after reinstalling.
- *Enabled, starting…* for more than a few seconds means Android has not bound it. Toggle it off and
  on, or reboot.

### The D-pad works, but pointer movement does not

- Moving the cursor needs only the overlay. If no cursor appears, check `adb logcat -s M2Remote` for
  "Overlay unavailable".
- Tap and scroll need gesture permission. If the phone says *"This projector does not allow gesture
  injection"*, the firmware's accessibility framework didn't grant `canPerformGestures`. Turn the
  service off and on again. Some vendor builds strip the feature, and nothing can be done without
  root.
- This is touch emulation, not a mouse. Apps that ignore touch input (some TV-only apps) won't react
  to taps. Use the D-pad there.

### Text entry doesn't work in a particular app

`ACTION_SET_TEXT` only works on standard Android text fields. It does **not** work in:

- WebViews and browsers' web pages (some accept the clipboard fallback),
- apps with custom-drawn keyboards (YouTube for TV, Netflix, most streaming apps),
- games.

Select the field on the projector first (arrows, then OK) so it has focus. There is no Enter key:
after sending, move to the app's search button and press OK.

### Some streaming apps ignore remote input

Apps built for TV often listen only for hardware D-pad keys or draw their UI with OpenGL, so they
expose no accessibility nodes. Virtual focus then finds nothing ("Nothing on this screen is
selectable"). Try touchpad mode. If that also fails, the app can only be controlled by real key
events (see "ADB development mode" in [REPORT.md](REPORT.md#future-extension)).

### The browser shows a connection error or "Offline"

- The pill turns red when a request fails. It recovers automatically on the next successful request
  (status is polled every 5 s while the tab is visible).
- *"This phone is no longer paired"* means the session expired (12 h idle or 30 days absolute), was
  revoked, or the projector's app data was cleared. Pair again.
- *STALE_TIMESTAMP* is retried automatically after re-syncing to the projector's clock. If it
  persists, the projector clock is jumping (no NTP); reload the page.

### Android blocks the APK

- *"App not installed"*: an older copy signed with a different key is installed. Run
  `adb uninstall com.m2smartremote` and install again.
- *"Install blocked"*: turn on **Unknown sources**.
- Play Protect warnings (if present) are expected for sideloaded apps. Choose *Install anyway*.

### The APK fails due to ABI or SDK incompatibility

- `INSTALL_FAILED_OLDER_SDK`: the device is below API 24. This APK requires 24+ (the projector is 25).
  Check with `adb shell getprop ro.build.version.sdk`.
- `INSTALL_FAILED_NO_MATCHING_ABIS` cannot happen: the app has no native code. If you add a native
  library later, include `armeabi-v7a`.
- `adb install` output and `adb logcat | grep PackageManager` show the exact reason.
