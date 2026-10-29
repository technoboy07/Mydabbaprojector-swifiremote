package com.m2smartremote.core;

/** Validation for text sent to the projector's focused text field. */
public final class TextInput {
    public static final int MAX_LENGTH = 500;

    private TextInput() {
    }

    /**
     * Returns the text with control characters (including newlines) removed, or null if it is
     * longer than {@link #MAX_LENGTH} code units.
     */
    public static String sanitize(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
