package com.termux.x11;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

/** IME preedit stays local. Only committed Unicode and editing keys reach X11. */
final class GameImeConnection extends BaseInputConnection {
    private final LorieView view;
    private final ImeComposition composing=new ImeComposition();
    private boolean closed;
    GameImeConnection(LorieView view){super(view,false);this.view=view;}
    private boolean ready(){return !closed&&view.acceptsIme();}
    boolean isClosed(){return closed;}
    void discard(){composing.clear();closed=true;}
    @Override public boolean setComposingText(CharSequence text,int cursor) {
        if(!ready())return false;composing.set(text);return true;
    }
    @Override public boolean commitText(CharSequence text,int cursor) {
        if(!ready())return false;composing.clear();return view.commitImeText(text==null?"":text.toString());
    }
    @Override public boolean finishComposingText() {
        if(!ready()){composing.clear();return false;}
        String text=composing.finish();return text.isEmpty()||view.commitImeText(text);
    }
    @Override public boolean deleteSurroundingText(int before,int after){return delete(before,after,false);}
    @Override public boolean deleteSurroundingTextInCodePoints(int before,int after){return delete(before,after,true);}
    private boolean delete(int before,int after,boolean codePoints) {
        if(!ready()||before<0||after<0||before>1024||after>1024)return false;
        before=composing.removeBefore(before,codePoints);
        for(int i=0;i<before;i++)view.imeKey(KeyEvent.KEYCODE_DEL);
        for(int i=0;i<after;i++)view.imeKey(KeyEvent.KEYCODE_FORWARD_DEL);
        return true;
    }
    @Override public CharSequence getTextBeforeCursor(int length,int flags) {
        String text=composing.text();return text.substring(Math.max(0,text.length()-Math.max(0,length)));
    }
    @Override public CharSequence getTextAfterCursor(int length,int flags){return "";}
    @Override public CharSequence getSelectedText(int flags){return null;}
    @Override public boolean setSelection(int start,int end){return false;}
    @Override public boolean setComposingRegion(int start,int end){return false;}
    @Override public boolean beginBatchEdit(){return ready();}
    @Override public boolean endBatchEdit(){return ready();}
    @Override public boolean performEditorAction(int action) {
        if(!ready())return false;
        if(!finishComposingText())return false;view.imeKey(KeyEvent.KEYCODE_ENTER);
        if(action!=EditorInfo.IME_ACTION_NONE)view.hideGameKeyboard();return true;
    }
    @Override public boolean sendKeyEvent(KeyEvent event) {
        if(!ready())return false;
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_FORWARD_DEL) {
            if(event.getAction()==KeyEvent.ACTION_DOWN)return delete(code==KeyEvent.KEYCODE_DEL?1:0,code==KeyEvent.KEYCODE_FORWARD_DEL?1:0,false);
            return true;
        }
        if(event.getAction()==KeyEvent.ACTION_UP)return true;
        if(event.getAction()==KeyEvent.ACTION_MULTIPLE&&event.getCharacters()!=null)return commitText(event.getCharacters(),1);
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
        if(code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_TAB||code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN) {
            finishComposingText();return view.imeKey(code);
        }
        int unicode=event.getUnicodeChar();
        return unicode>0&&unicode<=Character.MAX_CODE_POINT&&(unicode&android.view.KeyCharacterMap.COMBINING_ACCENT)==0&&commitText(new String(Character.toChars(unicode)),1);
    }
    @Override public void closeConnection(){discard();super.closeConnection();}
}
