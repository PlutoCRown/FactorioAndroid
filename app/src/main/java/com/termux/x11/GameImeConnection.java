package com.termux.x11;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

/** A full local editor: Android owns composition and only confirmed edits reach SDL. */
final class GameImeConnection extends BaseInputConnection {
    interface Target {
        boolean ready();

        boolean edit(int moveBefore, int deleteBefore, String insert, int moveAfter);

        boolean key(int code);

        void hide();
    }

    private final View view;
    private final Target target;
    private final ImeTextSync sync = new ImeTextSync();
    private boolean closed;
    private int batches;

    GameImeConnection(LorieView view) {
        this(
                view,
                new Target() {
                    public boolean ready() {
                        return view.acceptsIme();
                    }

                    public boolean edit(int before, int deletes, String insert, int after) {
                        return view.applyImeEdit(before, deletes, insert, after);
                    }

                    public boolean key(int code) {
                        return view.imeKey(code);
                    }

                    public void hide() {
                        view.hideGameKeyboard();
                    }
                });
    }

    GameImeConnection(View view, Target target) {
        super(view, true);
        this.view = view;
        this.target = target;
    }

    private boolean ready() {
        return !closed && target.ready();
    }

    boolean isClosed() {
        return closed;
    }

    void discard() {
        closed = true;
        sync.clear();
        getEditable().clear();
    }

    private boolean flush() {
        if (!ready()) return false;
        Editable text = getEditable();
        if (batches > 0 || getComposingSpanStart(text) >= 0) return true;
        int start = Selection.getSelectionStart(text), end = Selection.getSelectionEnd(text);
        if (start < 0 || end < 0 || start != end) return true;
        boolean ok = sync.sync(text.toString(), end, target::edit);
        if (!ok) discard(); // Never replay an edit whose delivery could be unconfirmed.
        return ok;
    }

    private void selectionChanged() {
        if (batches > 0 || closed) return;
        Editable text = getEditable();
        ((InputMethodManager)
                        view.getContext()
                                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                .updateSelection(
                        view,
                        Selection.getSelectionStart(text),
                        Selection.getSelectionEnd(text),
                        getComposingSpanStart(text),
                        getComposingSpanEnd(text));
    }

    @Override
    public boolean beginBatchEdit() {
        if (!ready()) return false;
        batches++;
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        if (batches > 0) batches--;
        if (!ready()) return false;
        boolean ok = flush();
        selectionChanged();
        return ok;
    }

    @Override
    public boolean setComposingText(CharSequence text, int cursor) {
        if (!ready() || text == null || text.length() > 65536) return false;
        boolean ok = super.setComposingText(text, cursor);
        selectionChanged();
        return ok;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (!ready()) return false;
        boolean ok = super.setComposingRegion(start, end);
        selectionChanged();
        return ok;
    }

    @Override
    public boolean setSelection(int start, int end) {
        if (!ready()) return false;
        boolean ok = super.setSelection(start, end) && flush();
        selectionChanged();
        return ok;
    }

    @Override
    public boolean commitText(CharSequence text, int cursor) {
        if (!ready() || text == null || text.length() > 65536) return false;
        boolean ok = super.commitText(text, cursor) && flush();
        selectionChanged();
        return ok;
    }

    @Override
    public boolean finishComposingText() {
        if (!ready()) return false;
        boolean ok = super.finishComposingText() && flush();
        selectionChanged();
        return ok;
    }

    @Override
    public boolean deleteSurroundingText(int before, int after) {
        return delete(before, after, false);
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        return delete(before, after, true);
    }

    private boolean delete(int before, int after, boolean points) {
        if (!ready() || before < 0 || after < 0 || before > 1024 || after > 1024) return false;
        Editable text = getEditable();
        int start = Selection.getSelectionStart(text), end = Selection.getSelectionEnd(text);
        if (start < 0 || end < 0) return false;
        boolean composing = getComposingSpanStart(text) >= 0;
        int availableBefore =
                points
                        ? Character.codePointCount(text, 0, Math.min(start, end))
                        : Math.min(start, end);
        int availableAfter =
                points
                        ? Character.codePointCount(text, Math.max(start, end), text.length())
                        : text.length() - Math.max(start, end);
        boolean ok =
                points
                        ? super.deleteSurroundingTextInCodePoints(before, after)
                        : super.deleteSurroundingText(before, after);
        if (ok) ok = flush();
        // Earlier game text is outside this connection's local buffer.
        if (ok && !composing) {
            for (int i = availableBefore; i < before; i++)
                ok = target.key(KeyEvent.KEYCODE_DEL) && ok;
            for (int i = availableAfter; i < after; i++)
                ok = target.key(KeyEvent.KEYCODE_FORWARD_DEL) && ok;
        }
        selectionChanged();
        return ok;
    }

    @Override
    public boolean performEditorAction(int action) {
        if (!ready() || !finishComposingText()) return false;
        boolean ok = target.key(KeyEvent.KEYCODE_ENTER);
        if (action != EditorInfo.IME_ACTION_NONE) target.hide();
        return ok;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (!ready()) return false;
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL)
            return delete(
                    code == KeyEvent.KEYCODE_DEL ? 1 : 0,
                    code == KeyEvent.KEYCODE_FORWARD_DEL ? 1 : 0,
                    false);
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null)
            return commitText(event.getCharacters(), 1);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_TAB)
            return finishComposingText() && target.key(code);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!finishComposingText()) return false;
            Editable text = getEditable();
            int at = Selection.getSelectionEnd(text);
            if (at < 0) return false;
            if ((code == KeyEvent.KEYCODE_DPAD_LEFT && at > 0)
                    || (code == KeyEvent.KEYCODE_DPAD_RIGHT && at < text.length())) {
                int next =
                        Character.offsetByCodePoints(
                                text, at, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return setSelection(next, next);
            }
            return target.key(code);
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
            return finishComposingText() && target.key(code);
        int unicode = event.getUnicodeChar();
        return unicode > 0
                && unicode <= Character.MAX_CODE_POINT
                && (unicode & android.view.KeyCharacterMap.COMBINING_ACCENT) == 0
                && commitText(new String(Character.toChars(unicode)), 1);
    }

    @Override
    public void closeConnection() {
        discard();
        super.closeConnection();
    }
}
