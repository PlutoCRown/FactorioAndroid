package com.termux.x11;

/** Touch mouse gestures independent of Android; every owned button has one release. */
public final class TouchMouseState {
    public static final float DEFAULT_SCROLL_DISTANCE=192;
    public interface Sink {
        void move(float x,float y);void leftButton(boolean down);void rightButton(boolean down);
        void scroll(float notches);void tap(float x,float y);
    }
    private final Sink sink;
    private final float slopSquared,scrollDistance;
    private final long longPressTimeout;
    private int viewportX,viewportY,viewportWidth,viewportHeight,activePointer=-1,scrollPointer=-1;
    private int sourceWidth=1280,sourceHeight=720;
    private float downX,downY,fingerX,fingerY,lastX,lastY,lastClickX,lastClickY,scrollY;
    private long downTime,presses,moves,drags,clicks,cancels,releases,rightPresses,rightReleases,scrollEvents;
    private double scrollNotches;
    private boolean leftHeld,rightHeld,tapCandidate,dragging,scrolling;
    public TouchMouseState(Sink sink,float slop,long timeout) {this(sink,slop,timeout,DEFAULT_SCROLL_DISTANCE);}
    public TouchMouseState(Sink sink,float slop,long timeout,float scrollDistance) {
        if(slop<0||timeout<=0||scrollDistance<=0||!Float.isFinite(scrollDistance))throw new IllegalArgumentException("Invalid gesture thresholds");
        this.sink=sink;slopSquared=slop*slop;longPressTimeout=timeout;this.scrollDistance=scrollDistance;
    }
    public void viewport(int x,int y,int width,int height) {
        if(x!=viewportX||y!=viewportY||width!=viewportWidth||height!=viewportHeight)cancel();
        viewportX=x;viewportY=y;viewportWidth=width;viewportHeight=height;
        if(width<=0||height<=0)cancel();
    }
    public void sourceSize(int width,int height) {
        if(width<2||height<2||width>16384||height>16384)throw new IllegalArgumentException("Invalid mouse source size");
        if(width!=sourceWidth||height!=sourceHeight)cancel();
        sourceWidth=width;sourceHeight=height;
    }
    private boolean finite(float x,float y){return Float.isFinite(x)&&Float.isFinite(y);}
    private boolean inside(float x,float y) {
        return finite(x,y)&&viewportWidth>0&&viewportHeight>0&&x>=viewportX&&y>=viewportY&&
            x<viewportX+viewportWidth&&y<viewportY+viewportHeight;
    }
    public void down(int id,float x,float y,long time) {
        cancel();if(id<0||!inside(x,y))return;
        activePointer=id;downX=fingerX=x;downY=fingerY=y;downTime=time;tapCandidate=true;
        // Defer left DOWN so a long press or second finger has no initial left click.
        sendPosition(x,y);
    }
    private void sendPosition(float x,float y) {
        lastX=Math.max(0,Math.min(sourceWidth-1,(x-viewportX)*sourceWidth/viewportWidth));
        lastY=Math.max(0,Math.min(sourceHeight-1,(y-viewportY)*sourceHeight/viewportHeight));
        sink.move(lastX,lastY);
    }
    private void leftDown(){if(!leftHeld){sink.leftButton(true);leftHeld=true;presses++;}}
    private void releaseButtons() {
        if(leftHeld){sink.leftButton(false);leftHeld=false;releases++;}
        if(rightHeld){sink.rightButton(false);rightHeld=false;rightReleases++;}
    }
    private void update(float x,float y) {
        float dx=x-downX,dy=y-downY;fingerX=x;fingerY=y;
        if(!rightHeld&&dx*dx+dy*dy>slopSquared) {
            tapCandidate=false;
            if(!dragging){dragging=true;drags++;sendPosition(downX,downY);leftDown();}
        }
        sendPosition(x,y);moves++;
    }
    public void move(int id,float x,float y) {
        if(activePointer<0||scrolling)return;
        if(id!=activePointer||!finite(x,y)){cancel();return;}
        update(x,y);
    }
    public boolean canLongPress(){return activePointer>=0&&tapCandidate&&!dragging&&!scrolling&&!rightHeld;}
    /** Called by the Android timer even when no MOVE event arrives. */
    public void longPress(int id,long time) {
        if(id!=activePointer||!canLongPress()||time<downTime||time-downTime<longPressTimeout||!inside(fingerX,fingerY))return;
        tapCandidate=false;releaseButtons();sendPosition(fingerX,fingerY);
        sink.rightButton(true);rightHeld=true;rightPresses++;
    }
    public void up(int id,float x,float y,long time) {
        if(activePointer<0)return;
        if(scrolling){cancel();return;}
        if(id!=activePointer||!finite(x,y)){cancel();return;}
        update(x,y);
        if(canLongPress()&&time>=downTime&&time-downTime>=longPressTimeout)longPress(id,time);
        boolean click=tapCandidate&&inside(x,y)&&time>=downTime&&time-downTime<longPressTimeout;
        if(click)leftDown();releaseButtons();clearGesture();
        if(click){clicks++;lastClickX=lastX;lastClickY=lastY;sink.tap(lastX,lastY);}
    }
    public void beginScroll(int first,float x,float y,int second,float x2,float y2) {
        boolean valid=activePointer==first&&!scrolling&&second>=0&&second!=first&&inside(x,y)&&inside(x2,y2);
        cancel();if(!valid)return;
        activePointer=first;scrollPointer=second;scrolling=true;scrollY=y/2+y2/2;
        sendPosition((x+x2)/2,scrollY);
    }
    public void scrollMove(int first,float x,float y,int second,float x2,float y2) {
        if(!scrolling)return;
        if(first!=activePointer||second!=scrollPointer||!finite(x,y)||!finite(x2,y2)){cancel();return;}
        float center=y/2+y2/2;
        // Forward each sample immediately, without a minimum step or splitting.
        // Bound malformed jumps without accumulating a delayed remainder.
        float notches=(float)Math.max(-1,Math.min(1,((double)scrollY-center)/scrollDistance));
        scrollY=center;
        if(notches!=0){sink.scroll(notches);scrollEvents++;scrollNotches+=Math.abs(notches);}
    }
    private void clearGesture(){activePointer=scrollPointer=-1;tapCandidate=dragging=scrolling=false;}
    /** Multitouch end, disabling, focus loss, resize and Surface loss cancel the whole gesture. */
    public void cancel() {
        if(activePointer>=0)cancels++;
        releaseButtons();clearGesture();
    }
    public int activePointer(){return activePointer;}
    public int scrollPointer(){return scrollPointer;}
    public boolean scrolling(){return scrolling;}
    public boolean held(){return leftHeld||rightHeld;}
    public boolean leftHeld(){return leftHeld;}
    public boolean rightHeld(){return rightHeld;}
    public long presses(){return presses;}
    public long moves(){return moves;}
    public long drags(){return drags;}
    public long clicks(){return clicks;}
    public long cancels(){return cancels;}
    public long releases(){return releases;}
    public long rightPresses(){return rightPresses;}
    public long rightReleases(){return rightReleases;}
    public long scrollEvents(){return scrollEvents;}
    public double scrollNotches(){return scrollNotches;}
    public float lastX(){return lastX;}
    public float lastY(){return lastY;}
    public float lastClickX(){return lastClickX;}
    public float lastClickY(){return lastClickY;}
}
