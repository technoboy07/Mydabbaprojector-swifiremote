package com.m2smartremote.core;

/** Volume and media-key actions; implemented with AudioManager, no accessibility required. */
public interface AudioBackend {
    ActionResult volume(Command command);

    ActionResult mediaKey(Command command);
}
