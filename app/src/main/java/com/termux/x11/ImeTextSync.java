package com.termux.x11;

/** Tracks only text submitted by this input connection, never reads game text. */
public final class ImeTextSync {
    public interface Sink {
        boolean apply(int moveBefore, int deleteBefore, String insert, int moveAfter);
    }

    private String sent = "";
    private int cursor;

    public void clear() {
        sent = "";
        cursor = 0;
    }

    public boolean sync(String updated, int selected, Sink sink) {
        if (updated == null
                || updated.length() > 65536
                || selected < 0
                || selected > updated.length()
                || splitsPair(updated, selected)) return false;
        if (sent.equals(updated)) {
            int move = distance(sent, cursor, selected);
            if (Math.abs(move) > 2048 || move != 0 && !sink.apply(move, 0, "", 0)) return false;
            cursor = selected;
            return true;
        }
        int prefix = 0, oldEnd = sent.length(), newEnd = updated.length();
        while (prefix < oldEnd && prefix < newEnd && sent.charAt(prefix) == updated.charAt(prefix))
            prefix++;
        if (splitsPair(sent, prefix) || splitsPair(updated, prefix)) prefix--;
        while (oldEnd > prefix
                && newEnd > prefix
                && sent.charAt(oldEnd - 1) == updated.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        if (splitsPair(sent, oldEnd) || splitsPair(updated, newEnd)) {
            oldEnd++;
            newEnd++;
        }
        int before = distance(sent, cursor, oldEnd), deletes = sent.codePointCount(prefix, oldEnd);
        String insert = updated.substring(prefix, newEnd);
        int after = distance(updated, newEnd, selected);
        if (Math.abs(before) > 2048 || deletes > 2048 || Math.abs(after) > 2048) return false;
        if ((before != 0 || deletes != 0 || !insert.isEmpty() || after != 0)
                && !sink.apply(before, deletes, insert, after)) return false;
        sent = updated;
        cursor = selected;
        return true;
    }

    private static int distance(String s, int from, int to) {
        return to >= from ? s.codePointCount(from, to) : -s.codePointCount(to, from);
    }

    private static boolean splitsPair(String s, int at) {
        return at > 0
                && at < s.length()
                && Character.isHighSurrogate(s.charAt(at - 1))
                && Character.isLowSurrogate(s.charAt(at));
    }
}
