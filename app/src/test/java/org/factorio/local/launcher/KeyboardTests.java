package org.factorio.local.launcher;

import com.termux.x11.ImeComposition;
import com.termux.x11.ImeTextSync;
import java.io.IOException;

public final class KeyboardTests {
    private static int checks;

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("keyboard check " + checks);
        checks++;
    }

    private static void reject(String value) throws Exception {
        try {
            KeyboardRequest.parse(value);
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("request accepted: " + value);
    }

    public static void main(String[] args) throws Exception {
        String url = "steam://open/keyboard?XPosition=-12&YPosition=120&Width=500&Height=24&Mode=";
        for (int mode = 0; mode < 4; mode++) {
            KeyboardRequest r = KeyboardRequest.parse(url + mode);
            check(
                    r.show
                            && r.x == -12
                            && r.y == 120
                            && r.width == 500
                            && r.height == 24
                            && r.mode == mode);
        }
        check(!KeyboardRequest.parse("steam://close/keyboard").show);
        for (String bad :
                new String[] {
                    null,
                    "",
                    "https://example.com/",
                    "steam://open/keyboard",
                    "steam://close/keyboard?x=1",
                    url + 4,
                    url + "0&Mode=1",
                    url + "0\n",
                    url + "0&Extra=1",
                    url.replace("Width=500", "Width=-1") + 0,
                    url.replace("Height=24", "Height=999999") + 0,
                    url.replace("XPosition=-12", "XPosition=1234567") + 0,
                    url.replace("Mode=", "Mode=%30") + "",
                    url.replace("Width=500&", "") + 0
                }) reject(bad);
        ImeComposition c = new ImeComposition();
        c.set("zhong");
        check(c.text().equals("zhong"));
        c.set("中");
        check(c.finish().equals("中"));
        check(c.finish().isEmpty());
        c.set("A😀B");
        check(c.removeBefore(1, true) == 0 && c.text().equals("A😀"));
        check(c.removeBefore(1, true) == 0 && c.text().equals("A"));
        check(c.removeBefore(3, true) == 2 && c.text().isEmpty());
        c.set("😀");
        check(c.removeBefore(1, false) == 0 && c.text().isEmpty());
        c.set("discard");
        c.clear();
        check(c.text().isEmpty());
        ImeTextSync sync = new ImeTextSync();
        StringBuilder game = new StringBuilder();
        int[] cursor = {0}, edits = {0};
        ImeTextSync.Sink sink =
                (before, deletes, text, after) -> {
                    edits[0]++;
                    cursor[0] = game.offsetByCodePoints(cursor[0], before);
                    int start = game.offsetByCodePoints(cursor[0], -deletes);
                    game.delete(start, cursor[0]);
                    cursor[0] = start;
                    game.insert(cursor[0], text);
                    cursor[0] += text.length();
                    cursor[0] = game.offsetByCodePoints(cursor[0], after);
                    return true;
                };
        check(sync.sync("ABC", 3, sink) && game.toString().equals("ABC"));
        check(sync.sync("ABC", 3, sink) && edits[0] == 1);
        check(sync.sync("ABCD", 4, sink) && game.toString().equals("ABCD"));
        check(sync.sync("ABD", 2, sink) && game.toString().equals("ABD") && cursor[0] == 2);
        check(sync.sync("AB中文D", 4, sink) && game.toString().equals("AB中文D"));
        check(sync.sync("AB😀D", 4, sink) && game.toString().equals("AB😀D") && cursor[0] == 4);
        check(!sync.sync("AB😀D", 3, sink));
        check(sync.sync("ABD", 2, sink) && game.toString().equals("ABD"));
        int prior = edits[0];
        check(sync.sync("ABD", 2, sink) && edits[0] == prior);
        System.out.println("KeyboardTests: " + checks + " checks passed");
    }
}
