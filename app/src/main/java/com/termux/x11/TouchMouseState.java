package com.termux.x11;

/** Single-finger absolute mouse state, independent of Android for release tests. */
public final class TouchMouseState {
    public interface Sink {void move(float x,float y);void leftButton(boolean down);void tap(float x,float y);}
    private final Sink sink;
    private final float slopSquared;
    private final long tapTimeout;
    private int viewportX,viewportY,viewportWidth,viewportHeight,activePointer=-1;
    private int sourceWidth=1280,sourceHeight=720;
    private float downX,downY,lastX,lastY,lastClickX,lastClickY;
    private long downTime,presses,moves,drags,clicks,cancels,releases;
    private boolean held,tapCandidate,dragging;
    public TouchMouseState(Sink sink,float slop,long tapTimeout) {
        this.sink=sink;slopSquared=slop*slop;this.tapTimeout=tapTimeout;
    }
    public void viewport(int x,int y,int width,int height) {
        viewportX=x;viewportY=y;viewportWidth=width;viewportHeight=height;
        if(width<=0||height<=0)cancel();
    }
    public void sourceSize(int width,int height) {
        if(width<2||height<2||width>16384||height>16384)throw new IllegalArgumentException("Invalid mouse source size");
        if(width!=sourceWidth||height!=sourceHeight)cancel();
        sourceWidth=width;sourceHeight=height;
    }
    private boolean finite(float x,float y){return !Float.isNaN(x)&&!Float.isNaN(y)&&!Float.isInfinite(x)&&!Float.isInfinite(y);}
    private boolean inside(float x,float y) {
        return finite(x,y)&&viewportWidth>0&&viewportHeight>0&&x>=viewportX&&y>=viewportY&&
            x<viewportX+viewportWidth&&y<viewportY+viewportHeight;
    }
    public void down(int id,float x,float y,long time) {
        cancel();
        if(id<0||!inside(x,y))return;
        activePointer=id;downX=x;downY=y;downTime=time;tapCandidate=true;dragging=false;
        sendPosition(x,y);sink.leftButton(true);held=true;presses++;
    }
    private void sendPosition(float x,float y) {
        lastX=Math.max(0,Math.min(sourceWidth-1,(x-viewportX)*sourceWidth/viewportWidth));
        lastY=Math.max(0,Math.min(sourceHeight-1,(y-viewportY)*sourceHeight/viewportHeight));
        sink.move(lastX,lastY);
    }
    private void update(float x,float y) {
        float dx=x-downX,dy=y-downY;
        if(dx*dx+dy*dy>slopSquared) {
            tapCandidate=false;if(!dragging){dragging=true;drags++;}
        }
        sendPosition(x,y);moves++;
    }
    public void move(int id,float x,float y) {
        if(!held)return;
        if(id!=activePointer||!finite(x,y)){cancel();return;}
        update(x,y);
    }
    public void up(int id,float x,float y,long time) {
        if(!held)return;
        if(id!=activePointer||!finite(x,y)){cancel();return;}
        update(x,y);
        boolean click=tapCandidate&&inside(x,y)&&time>=downTime&&time-downTime<=tapTimeout;
        sink.leftButton(false);held=false;releases++;activePointer=-1;tapCandidate=false;dragging=false;
        if(click){clicks++;lastClickX=lastX;lastClickY=lastY;sink.tap(lastX,lastY);}
    }
    /** Cancel/multitouch/focus or Surface loss always releases an owned button. */
    public void cancel() {
        if(held){sink.leftButton(false);releases++;cancels++;}
        held=false;activePointer=-1;tapCandidate=false;dragging=false;
    }
    public int activePointer(){return activePointer;}
    public boolean held(){return held;}
    public long presses(){return presses;}
    public long moves(){return moves;}
    public long drags(){return drags;}
    public long clicks(){return clicks;}
    public long cancels(){return cancels;}
    public long releases(){return releases;}
    public float lastX(){return lastX;}
    public float lastY(){return lastY;}
    public float lastClickX(){return lastClickX;}
    public float lastClickY(){return lastClickY;}
}
