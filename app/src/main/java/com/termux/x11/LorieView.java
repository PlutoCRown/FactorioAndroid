/* SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal Surface/JNI adapter derived from Termux:X11's public native interface.
 * Upstream commit fa3a8b430e2896a19f44c99a9cb056254615ae06.
 */
package com.termux.x11;
import android.view.*;
import dalvik.annotation.optimization.CriticalNative;

public final class LorieView extends SurfaceView implements SurfaceHolder.Callback {
    public final MainActivity activity;
    private long pointer;
    private int viewportX,viewportY,viewportWidth,viewportHeight;
    private final int renderScalePercent;
    private int renderWidth,renderHeight;
    private final TouchMouseState touch;
    private int keyEvents,escapeEvents;
    private final java.util.Map<Integer,Integer> pressedKeys=new java.util.HashMap<>();
    public LorieView(MainActivity activity,int renderScalePercent) {
        super(activity);this.activity=activity;this.renderScalePercent=renderScalePercent;
        touch=new TouchMouseState(new TouchMouseState.Sink() {
            public void move(float x,float y){if(pointer!=0&&isConnected())sendMouseEvent(pointer,x,y,0,false,false);}
            public void leftButton(boolean down){if(pointer!=0&&isConnected())sendMouseEvent(pointer,0,0,1,down,false);}
            public void tap(float x,float y){performClick();}
        },ViewConfiguration.get(activity).getScaledTouchSlop(),ViewConfiguration.getLongPressTimeout());
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
    public void dispose() {touch.cancel();releaseKeys();if(pointer!=0) {long old=pointer;pointer=0;nativeDestroy(old);}}
    @Override public void surfaceCreated(SurfaceHolder holder) {surfaceChanged(pointer,holder.getSurface());activity.surfaceReady();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {surfaceChanged(pointer,holder.getSurface());updateViewport(width,height);}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {touch.cancel();releaseKeys();activity.surfaceLost();if(pointer!=0)surfaceChanged(pointer,null);}
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(pointer==0||!isConnected()) {touch.cancel();return super.onTouchEvent(event);}
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_CANCEL){touch.cancel();return true;}
        if(!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return super.onTouchEvent(event);
        if(action==MotionEvent.ACTION_POINTER_DOWN||
            action==MotionEvent.ACTION_POINTER_UP||event.getPointerCount()!=1) {touch.cancel();return true;}
        if(action==MotionEvent.ACTION_DOWN) {
            requestFocus();touch.down(event.getPointerId(0),event.getX(0),event.getY(0),event.getEventTime());return true;
        }
        int index=event.findPointerIndex(touch.activePointer());
        if(index<0){touch.cancel();return true;}
        if(action==MotionEvent.ACTION_MOVE) {
            touch.move(event.getPointerId(index),event.getX(index),event.getY(index));return true;
        }
        if(action==MotionEvent.ACTION_UP) {
            touch.up(event.getPointerId(index),event.getX(index),event.getY(index),event.getEventTime());return true;
        }
        return true;
    }
    @Override public boolean performClick() {super.performClick();return true;}
    public boolean forwardEscape() {
        if(pointer==0||!isConnected())return false;
        touch.cancel();
        // Linux evdev KEY_ESC=1; the native keyboard path handles its X11 offset.
        boolean down=sendKeyEvent(pointer,1,KeyEvent.KEYCODE_ESCAPE,true);
        boolean up=sendKeyEvent(pointer,1,KeyEvent.KEYCODE_ESCAPE,false);
        keyEvents+=2;if(down&&up)escapeEvents++;
        return down&&up;
    }
    public boolean forwardKeyboardEvent(KeyEvent event) {
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
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(!focused){touch.cancel();releaseKeys();}
    }
    public org.json.JSONObject inputState() throws Exception {
        return new org.json.JSONObject().put("touchLeftClicks",touch.clicks()).put("lastClickX",touch.lastClickX()).put("lastClickY",touch.lastClickY())
            .put("touchLeftPresses",touch.presses()).put("touchLeftReleases",touch.releases()).put("touchMoveEvents",touch.moves())
            .put("touchDrags",touch.drags()).put("touchCanceledGestures",touch.cancels()).put("touchLeftHeld",touch.held())
            .put("touchActivePointer",touch.activePointer()).put("lastTouchX",touch.lastX()).put("lastTouchY",touch.lastY())
            .put("keyboardEventsForwarded",keyEvents).put("androidBackEscapeEvents",escapeEvents).put("pressedKeys",pressedKeys.size()).put("nativeGamepadBridgeReady",false)
            .put("viewportX",viewportX).put("viewportY",viewportY).put("viewportWidth",viewportWidth).put("viewportHeight",viewportHeight)
            .put("viewWidth",getWidth()).put("viewHeight",getHeight())
            .put("renderWidth",renderWidth).put("renderHeight",renderHeight).put("renderScalePercent",renderScalePercent);
    }
    public void resetIme() {}
    public void setClipboardText(String text) {}
    public void requestClipboard() {}
    public void onSyncReply(int serial) {activity.syncReply(serial);}
    public void setRendererViewport(int x,int y,int width,int height,float sourceX,float sourceY,float sourceWidth,float sourceHeight) {}
    private void updateViewport(int width,int height) {
        // Split/freeform transitions may temporarily publish a 0- or 1-pixel buffer.
        // Preserve the last valid render target until a usable Surface arrives.
        if(width<2||height<2||width>16384||height>16384){touch.cancel();return;}
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
