package com.termux.x11;

/** The IME owns preedit; the game receives final commits only. No game-text cache. */
public final class ImeComposition {
    private String text = "";

    public void set(CharSequence value) {
        text = value == null ? "" : value.toString();
    }

    public String text() {
        return text;
    }

    public void clear() {
        text = "";
    }

    public String finish() {
        String value = text;
        clear();
        return value;
    }

    public int removeBefore(int units, boolean codePoints) {
        int removed =
                codePoints
                        ? Math.min(units, text.codePointCount(0, text.length()))
                        : Math.min(units, text.length());
        int end =
                codePoints
                        ? text.offsetByCodePoints(text.length(), -removed)
                        : text.length() - removed;
        if (end > 0
                && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        text = text.substring(0, end);
        return units - removed;
    }
}
