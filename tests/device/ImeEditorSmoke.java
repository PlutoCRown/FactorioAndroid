package com.termux.x11;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

/** Real Android BaseInputConnection/Editable against the candidate APK. No game or IME UI. */
public final class ImeEditorSmoke {
    static int checks;

    static final class Target implements GameImeConnection.Target {
        StringBuilder game = new StringBuilder();
        int cursor, edits;
        boolean active = true, hidden;

        public boolean ready() {
            return active;
        }

        public boolean edit(int before, int deletes, String text, int after) {
            cursor = game.offsetByCodePoints(cursor, before);
            int start = game.offsetByCodePoints(cursor, -deletes);
            game.delete(start, cursor);
            cursor = start;
            game.insert(cursor, text);
            cursor += text.length();
            cursor = game.offsetByCodePoints(cursor, after);
            edits++;
            return true;
        }

        public boolean key(int code) {
            if (code == KeyEvent.KEYCODE_DEL && cursor > 0) {
                int start = game.offsetByCodePoints(cursor, -1);
                game.delete(start, cursor);
                cursor = start;
            }
            if (code == KeyEvent.KEYCODE_FORWARD_DEL && cursor < game.length())
                game.delete(cursor, game.offsetByCodePoints(cursor, 1));
            return true;
        }

        public void hide() {
            hidden = true;
        }
    }

    static void check(boolean value) {
        if (!value) throw new AssertionError("Editor regression " + checks);
        checks++;
    }

    static void run(String pkg) throws Exception {
        Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        java.lang.reflect.Method get = type.getDeclaredMethod("getSystemContext");
        get.setAccessible(true);
        Context context = ((Context) get.invoke(thread)).createPackageContext(pkg, 0);
        View view = new View(context);
        Target target = new Target();
        GameImeConnection c = new GameImeConnection(view, target);
        check(
                c.setComposingText("A", 1)
                        && c.setComposingText("AB", 1)
                        && c.setComposingText("ABC", 1)
                        && target.game.length() == 0);
        check(c.finishComposingText() && target.game.toString().equals("ABC") && target.edits == 1);
        check(c.finishComposingText() && target.edits == 1);
        check(c.getTextBeforeCursor(20, 0).toString().equals("ABC"));
        check(
                c.setComposingRegion(0, 3)
                        && c.setComposingText("teh", 1)
                        && target.game.toString().equals("ABC"));
        check(c.commitText("the", 1) && target.game.toString().equals("the"));
        check(
                c.setComposingRegion(0, 3)
                        && c.setComposingText("zhong", 1)
                        && target.game.toString().equals("the"));
        check(c.commitText("中文", 1) && target.game.toString().equals("中文"));
        check(c.commitText("\ud83d\ude00", 1) && target.game.toString().equals("中文\ud83d\ude00"));
        check(c.deleteSurroundingTextInCodePoints(1, 0) && target.game.toString().equals("中文"));
        check(
                c.beginBatchEdit()
                        && c.setComposingText("ni", 1)
                        && c.commitText("你", 1)
                        && target.game.toString().equals("中文"));
        check(c.endBatchEdit() && target.game.toString().equals("中文你"));
        check(
                c.setSelection(1, 1)
                        && c.commitText("A", 1)
                        && target.game.toString().equals("中A文你"));
        check(c.deleteSurroundingText(1, 0) && target.game.toString().equals("中文你"));
        c.discard();
        check(!c.commitText("rejected", 1) && target.game.toString().equals("中文你"));
        Target existing = new Target();
        existing.game.append("old");
        existing.cursor = 3;
        GameImeConnection d = new GameImeConnection(view, existing);
        check(d.deleteSurroundingText(1, 0) && existing.game.toString().equals("ol"));
        check(d.setComposingText("discard", 1));
        d.discard();
        check(existing.game.toString().equals("ol"));
        System.out.println(
                "IME_EDITOR_SMOKE checks="
                        + checks
                        + " passed=true realAndroidEditable=true gameStarted=false imeShown=false");
    }

    public static void main(String[] args) {
        try {
            run(args[0]);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
