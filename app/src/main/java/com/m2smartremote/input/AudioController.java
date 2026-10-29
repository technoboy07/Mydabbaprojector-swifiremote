package com.m2smartremote.input;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.m2smartremote.core.ActionResult;
import com.m2smartremote.core.ApiError;
import com.m2smartremote.core.AudioBackend;
import com.m2smartremote.core.Command;

/**
 * Volume via AudioManager stream adjustment and media keys via
 * {@link AudioManager#dispatchMediaKeyEvent}, which delivers the key to the app that currently
 * owns the media session. Both are public APIs that need no special permission.
 */
public final class AudioController implements AudioBackend {
    private final AudioManager audio;

    public AudioController(Context context) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public ActionResult volume(Command command) {
        int direction;
        switch (command) {
            case VOLUME_UP:
                direction = AudioManager.ADJUST_RAISE;
                break;
            case VOLUME_DOWN:
                direction = AudioManager.ADJUST_LOWER;
                break;
            case MUTE:
                direction = AudioManager.ADJUST_TOGGLE_MUTE;
                break;
            default:
                return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException e) {
            // Thrown when Do Not Disturb policy forbids the change.
            return ActionResult.fail(ApiError.ACTION_FAILED, "The projector refused the volume change.");
        }
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return ActionResult.ok("Volume " + vol + "/" + max);
    }

    @Override
    public ActionResult mediaKey(Command command) {
        int keyCode;
        switch (command) {
            case MEDIA_PLAY_PAUSE:
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case MEDIA_NEXT:
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                break;
            case MEDIA_PREVIOUS:
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                break;
            case MEDIA_STOP:
                keyCode = KeyEvent.KEYCODE_MEDIA_STOP;
                break;
            case MEDIA_REWIND:
                keyCode = KeyEvent.KEYCODE_MEDIA_REWIND;
                break;
            case MEDIA_FAST_FORWARD:
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                break;
            default:
                return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
        return ActionResult.ok("Sent to the app that is playing media.");
    }
}
