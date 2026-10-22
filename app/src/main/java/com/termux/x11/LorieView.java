/* SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal Surface/JNI adapter derived from Termux:X11's public native interface.
 * Upstream commit fa3a8b430e2896a19f44c99a9cb056254615ae06.
 */
package com.termux.x11;
import android.view.*;
import android.view.inputmethod.*;
import android.text.InputType;
import java.nio.charset.StandardCharsets;
import org.factorio.local.launcher.KeyboardRequest;
import dalvik.annotation.optimization.CriticalNative;

public final class LorieView extends SurfaceView implements SurfaceHolder.Callback {
    public final MainActivity activity;
    private long pointer;
    private int viewportX,viewportY,viewportWidth,viewportHeight;
    private final int renderScalePercent;
    private int renderWidth,renderHeight;
    private final TouchMouseState touch;
    private final boolean touchMouseSimulation;
    private final Runnable longPressCheck;
    private int keyEvents,escapeEvents;
    private boolean keyboardRequested,imeVisible,keyboardSuppressed,windowResumed,nativeImeVisible;
    private int keyboardMode,imeCommits,imeCodePoints;
    private GameImeConnection imeConnection;
    private final java.util.Map<Integer,Integer> pressedKeys=new java.util.HashMap<>();
    public LorieView(MainActivity activity,int renderScalePercent,boolean touchMouseSimulation) {
        super(activity);this.activity=activity;this.renderScalePercent=renderScalePercent;this.touchMouseSimulation=touchMouseSimulation;
        touch=new TouchMouseState(new TouchMouseState.Sink() {
            public void move(float x,float y){if(pointer!=0&&isConnected())sendMouseEvent(pointer,x,y,0,false,false);}
            public void leftButton(boolean down){if(pointer!=0&&isConnected())sendMouseEvent(pointer,0,0,1,down,false);}
            public void rightButton(boolean down){if(pointer!=0&&isConnected())sendMouseEvent(pointer,0,0,3,down,false);}
            // Xlorie accepts fractional wheel units; 120 units represent one notch.
            public void scroll(float notches){if(pointer!=0&&isConnected())sendMouseEvent(pointer,0,notches*120f,4,false,true);}
            public void tap(float x,float y){performClick();}
        },ViewConfiguration.get(activity).getScaledTouchSlop(),ViewConfiguration.getLongPressTimeout(),96*getResources().getDisplayMetrics().density);
        longPressCheck=()->{if(touchMouseSimulation&&pointer!=0&&isConnected())touch.longPress(touch.activePointer(),android.os.SystemClock.uptimeMillis());};
        setClickable(true);setFocusable(true);setFocusableInTouchMode(true);requestFocus();
        pointer=nativeInit();getHolder().addCallback(this);
    }
    public void attach(int fd) {
        connect(pointer,fd);updateViewport(getWidth(),getHeight());
        if(renderWidth<=0||renderHeight<=0)throw new IllegalStateException("Game Surface is not measured");
        sendWindowChange(pointer,renderWidth,renderHeight,60,"Factorio");sendSync(pointer,93);
    }
    public int renderWidth(){return renderWidth;}
    public int renderHeight(){return renderHeight;}
    public boolean isConnected() {return connected(pointer);}
    public void cancelTouch(){removeCallbacks(longPressCheck);touch.cancel();}
    public void dispose() {setGameKeyboard(null);cancelTouch();releaseKeys();if(pointer!=0) {long old=pointer;pointer=0;nativeDestroy(old);}}
    @Override public void surfaceCreated(SurfaceHolder holder) {surfaceChanged(pointer,holder.getSurface());activity.surfaceReady();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {surfaceChanged(pointer,holder.getSurface());updateViewport(width,height);}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {cancelTouch();releaseKeys();activity.surfaceLost();if(pointer!=0)surfaceChanged(pointer,null);}
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(pointer==0||!isConnected()) {cancelTouch();return super.onTouchEvent(event);}
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_CANCEL){cancelTouch();return true;}
        if(!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return super.onTouchEvent(event);
        if(!touchMouseSimulation){cancelTouch();return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPressCheck);
            int first=event.findPointerIndex(touch.activePointer()),second=event.getActionIndex();
            if(event.getPointerCount()==2&&first>=0&&first!=second)
                touch.beginScroll(event.getPointerId(first),event.getX(first),event.getY(first),event.getPointerId(second),event.getX(second),event.getY(second));
            else cancelTouch();
            return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){cancelTouch();return true;}
        if(touch.scrolling()&&action==MotionEvent.ACTION_MOVE&&event.getPointerCount()==2) {
            int a=event.findPointerIndex(touch.activePointer()),b=event.findPointerIndex(touch.scrollPointer());
            if(a<0||b<0)cancelTouch();else touch.scrollMove(event.getPointerId(a),event.getX(a),event.getY(a),event.getPointerId(b),event.getX(b),event.getY(b));
            return true;
        }
        if(event.getPointerCount()!=1){cancelTouch();return true;}
        if(action==MotionEvent.ACTION_DOWN) {
            cancelTouch();requestFocus();touch.down(event.getPointerId(0),event.getX(0),event.getY(0),event.getEventTime());
            if(touch.canLongPress())postDelayed(longPressCheck,ViewConfiguration.getLongPressTimeout());return true;
        }
        int index=event.findPointerIndex(touch.activePointer());
        if(index<0){cancelTouch();return true;}
        if(action==MotionEvent.ACTION_MOVE) {
            touch.move(event.getPointerId(index),event.getX(index),event.getY(index));
            if(!touch.canLongPress())removeCallbacks(longPressCheck);return true;
        }
        if(action==MotionEvent.ACTION_UP) {
            removeCallbacks(longPressCheck);touch.up(event.getPointerId(index),event.getX(index),event.getY(index),event.getEventTime());return true;
        }
        return true;
    }
    @Override public boolean performClick() {super.performClick();return true;}
    public boolean forwardEscape() {
        if(pointer==0||!isConnected())return false;
        cancelTouch();
        // Linux evdev KEY_ESC=1; the native keyboard path handles its X11 offset.
        boolean down=sendKeyEvent(pointer,1,KeyEvent.KEYCODE_ESCAPE,true);
        boolean up=sendKeyEvent(pointer,1,KeyEvent.KEYCODE_ESCAPE,false);
        keyEvents+=2;if(down&&up)escapeEvents++;
        return down&&up;
    }
    public boolean forwardKeyboardEvent(KeyEvent event) {
        if((event.getFlags()&KeyEvent.FLAG_SOFT_KEYBOARD)!=0)return false;
        if(pointer==0||!isConnected()||!event.isFromSource(InputDevice.SOURCE_KEYBOARD)||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD))return false;
        int code=event.getKeyCode(),scan=event.getScanCode(),action=event.getAction();
        if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_POWER||
            code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_VOLUME_MUTE)return false;
        if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return false;
        // Physical scan codes are Linux evdev codes. Bound fallback Android
        // codes to the established keyboard range of the pinned native map.
        if(scan==0&&(code<1||code>187))return false;
        boolean down=action==KeyEvent.ACTION_DOWN;
        if(down&&pressedKeys.containsKey(code))return true;
        if(down)pressedKeys.put(code,scan);else pressedKeys.remove(code);
        keyEvents++;return sendKeyEvent(pointer,scan,code,down);
    }
    private void releaseKeys() {
        if(pointer!=0&&isConnected())for(java.util.Map.Entry<Integer,Integer> key:pressedKeys.entrySet())sendKeyEvent(pointer,key.getValue(),key.getKey(),false);
        pressedKeys.clear();
    }
    public org.json.JSONObject inputState() throws Exception {
        return new org.json.JSONObject().put("touchLeftClicks",touch.clicks()).put("lastClickX",touch.lastClickX()).put("lastClickY",touch.lastClickY())
            .put("touchLeftPresses",touch.presses()).put("touchLeftReleases",touch.releases()).put("touchMoveEvents",touch.moves())
            .put("touchDrags",touch.drags()).put("touchCanceledGestures",touch.cancels()).put("touchLeftHeld",touch.leftHeld())
            .put("touchMouseSimulationEnabled",touchMouseSimulation).put("touchRightHeld",touch.rightHeld())
            .put("touchRightPresses",touch.rightPresses()).put("touchRightReleases",touch.rightReleases())
            .put("touchScrollEvents",touch.scrollEvents()).put("touchScrollNotches",touch.scrollNotches()).put("touchScrolling",touch.scrolling())
            .put("touchActivePointer",touch.activePointer()).put("lastTouchX",touch.lastX()).put("lastTouchY",touch.lastY())
            .put("keyboardEventsForwarded",keyEvents).put("androidBackEscapeEvents",escapeEvents).put("pressedKeys",pressedKeys.size()).put("nativeGamepadBridgeReady",false)
            .put("viewportX",viewportX).put("viewportY",viewportY).put("viewportWidth",viewportWidth).put("viewportHeight",viewportHeight)
            .put("viewWidth",getWidth()).put("viewHeight",getHeight())
            .put("imeRequested",keyboardRequested).put("imeVisible",imeVisible).put("imeSuppressed",keyboardSuppressed).put("imeSdlVisible",nativeImeVisible)
            .put("imeMode",keyboardMode).put("imeCommits",imeCommits).put("imeCodePoints",imeCodePoints)
            .put("renderWidth",renderWidth).put("renderHeight",renderHeight).put("renderScalePercent",renderScalePercent);
    }
    public void setGameKeyboard(KeyboardRequest request) {
        keyboardRequested=request!=null&&request.show;keyboardSuppressed=false;
        nativeImeVisible=keyboardRequested;
        if(request!=null)keyboardMode=request.mode;
        if(imeConnection!=null){imeConnection.discard();imeConnection=null;}
        InputMethodManager manager=(InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        manager.restartInput(this);
        if(keyboardRequested&&windowResumed)showGameKeyboard();else manager.hideSoftInputFromWindow(getWindowToken(),0);
    }
    public boolean acceptsIme(){return keyboardRequested&&windowResumed&&pointer!=0&&isConnected();}
    public boolean keyboardRequested(){return keyboardRequested;}
    public void showGameKeyboard() {
        if(!acceptsIme())return;keyboardSuppressed=false;requestFocus();
        if(activity.hasDirectImeText()&&!nativeImeVisible) {
            if(!activity.sendImeVisibility(true)){keyboardSuppressed=true;return;}
            nativeImeVisible=true;
        }
        InputMethodManager manager=(InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(imeConnection==null||imeConnection.isClosed())manager.restartInput(this);
        manager.showSoftInput(this,0,new android.os.ResultReceiver(new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int result,android.os.Bundle data) {
                if(android.os.Build.VERSION.SDK_INT<30&&keyboardRequested&&windowResumed)
                    imeVisibilityChanged(result==InputMethodManager.RESULT_SHOWN||result==InputMethodManager.RESULT_UNCHANGED_SHOWN);
            }
        });
    }
    public boolean hideGameKeyboard() {
        if(!imeVisible)return false;keyboardSuppressed=true;
        if(imeConnection!=null){imeConnection.discard();imeConnection=null;}
        ((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);
        imeVisibilityChanged(false);return true;
    }
    public void imeVisibilityChanged(boolean visible){
        boolean wasVisible=imeVisible;imeVisible=visible;
        if(wasVisible&&!visible&&windowResumed){
            keyboardSuppressed=true;
            if(imeConnection!=null){imeConnection.discard();imeConnection=null;}
        }
        if(windowResumed&&keyboardRequested&&activity.hasDirectImeText()&&nativeImeVisible!=visible&&activity.sendImeVisibility(visible))nativeImeVisible=visible;
    }
    public void setImeResumed(boolean resumed) {
        windowResumed=resumed;
        if(!resumed){if(imeConnection!=null){imeConnection.discard();imeConnection=null;}
            ((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);}
        else if(keyboardRequested&&!keyboardSuppressed)post(this::showGameKeyboard);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(!focused){cancelTouch();releaseKeys();}
        else if(keyboardRequested&&windowResumed&&!keyboardSuppressed&&!imeVisible)post(this::showGameKeyboard);
    }
    @Override public boolean onCheckIsTextEditor(){return keyboardRequested;}
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if(!keyboardRequested)return null;
        if(imeConnection!=null)imeConnection.discard();
        info.inputType=keyboardMode==3?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED:
            InputType.TYPE_CLASS_TEXT|(keyboardMode==2?InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:0)|
            (keyboardMode==1?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0);
        info.imeOptions=EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_FLAG_NO_EXTRACT_UI|
            (keyboardMode==1?EditorInfo.IME_ACTION_NONE:EditorInfo.IME_ACTION_DONE);
        info.initialSelStart=0;info.initialSelEnd=0;
        imeConnection=new GameImeConnection(this);return imeConnection;
    }
    @Override public boolean onKeyPreIme(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_BACK&&imeVisible){if(event.getAction()==KeyEvent.ACTION_UP)hideGameKeyboard();return true;}
        return super.onKeyPreIme(code,event);
    }
    boolean commitImeText(String text) {
        return applyImeEdit(0,0,text,0);
    }
    boolean applyImeEdit(int before,int deletes,String text,int after) {
        if(!acceptsIme()||text.length()>65536)return false;
        boolean ok=true;
        if(activity.hasDirectImeText())ok=activity.sendImeEdit(before,deletes,text,after);
        else {
            for(int i=0;i<Math.abs(before);i++)ok=imeKey(before<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT)&&ok;
            for(int i=0;i<deletes;i++)ok=imeKey(KeyEvent.KEYCODE_DEL)&&ok;
            if(ok&&!text.isEmpty())sendTextEvent(pointer,text.getBytes(StandardCharsets.UTF_8));
            for(int i=0;i<Math.abs(after);i++)ok=imeKey(after<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT)&&ok;
        }
        if(ok&&!text.isEmpty()){imeCommits++;imeCodePoints+=text.codePointCount(0,text.length());}
        return ok;
    }
    boolean imeKey(int code) {
        if(!acceptsIme())return false;
        if(activity.hasDirectImeText())return activity.sendImeKey(code);
        int scan;
        switch(code){case KeyEvent.KEYCODE_DEL:scan=14;break;case KeyEvent.KEYCODE_FORWARD_DEL:scan=111;break;
            case KeyEvent.KEYCODE_ENTER:scan=28;break;case KeyEvent.KEYCODE_TAB:scan=15;break;
            case KeyEvent.KEYCODE_DPAD_LEFT:scan=105;break;case KeyEvent.KEYCODE_DPAD_RIGHT:scan=106;break;
            case KeyEvent.KEYCODE_DPAD_UP:scan=103;break;case KeyEvent.KEYCODE_DPAD_DOWN:scan=108;break;default:return false;}
        boolean down=sendKeyEvent(pointer,scan,code,true),up=sendKeyEvent(pointer,scan,code,false);return down&&up;
    }
    // X11 top-level focus/mouse callbacks are not game text-field requests.
    public void resetIme() {}
    public void setClipboardText(String text) {}
    public void requestClipboard() {}
    public void onSyncReply(int serial) {activity.syncReply(serial);}
    public void setRendererViewport(int x,int y,int width,int height,float sourceX,float sourceY,float sourceWidth,float sourceHeight) {}
    private void updateViewport(int width,int height) {
        // Split/freeform transitions may temporarily publish a 0- or 1-pixel buffer.
        // Preserve the last valid render target until a usable Surface arrives.
        if(width<2||height<2||width>16384||height>16384){cancelTouch();return;}
        if(pointer==0)return;
        RenderSize size=new RenderSize(width,height,renderScalePercent);
        boolean changed=size.width!=renderWidth||size.height!=renderHeight;
        renderWidth=size.width;renderHeight=size.height;
        viewportX=0;viewportY=0;viewportWidth=width;viewportHeight=height;
        touch.sourceSize(renderWidth,renderHeight);touch.viewport(0,0,width,height);
        setViewport(pointer,0,0,width,height,renderWidth,renderHeight,0);
        activity.windowSizeReady(renderWidth,renderHeight,width,height,renderScalePercent);
        if(changed&&isConnected())sendWindowChange(pointer,renderWidth,renderHeight,60,"Factorio");
    }

    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void surfaceChanged(long ptr,Surface surface);
    private native void setFiltering(long ptr,int filtering);
    private native void setClipboardSyncEnabled(long ptr,boolean enabled,boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr,byte[] text);
    private native void sendWindowChange(long ptr,int width,int height,int framerate,String name);
    private native void setViewport(long ptr,int x,int y,int width,int height,int expectedWidth,int expectedHeight,int hiddenBottom);
    private native void setRendererZoom(long ptr,int percent);
    private native void setZoomAnchor(long ptr,float sourceX,float sourceY,float fracX,float fracY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr,boolean enabled);
    private native long getCursorPosition(long ptr);
    private native void sendSync(long ptr,int serial);
    private static native void connect(long ptr,int fd);
    @CriticalNative private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr,int fd);
    private native void sendMouseEvent(long ptr,float x,float y,int button,boolean down,boolean relative);
    private native void sendTouchEvent(long ptr,int action,int id,int x,int y);
    private native void sendStylusEvent(long ptr,float x,float y,int pressure,int tiltX,int tiltY,int orientation,int buttons,boolean eraser,boolean mouse);
    private native void requestStylusEnabled(long ptr,boolean enabled);
    private native void sendLockKeysState(long ptr,int state);
    private native boolean sendKeyEvent(long ptr,int scanCode,int keyCode,boolean down);
    private native void sendTextEvent(long ptr,byte[] text);
    @CriticalNative private static native boolean requestConnection(long ptr);
    @CriticalNative public static native long getLastInputTimestamp();
    @CriticalNative public static native void markUserActivity();
}
