package com.termux.x11;

/** Absolute hardware pointer input, independent of touch gestures and Android UI. */
public final class PhysicalMouseState {
    public interface Sink {
        void move(float x,float y);void button(int button,boolean down);void scroll(float x,float y);
    }
    private final Sink sink;
    private int width,height,sourceWidth,sourceHeight,buttons;
    private long moves,presses,releases,scrolls;
    public PhysicalMouseState(Sink sink){this.sink=sink;}
    public void viewport(int width,int height,int sourceWidth,int sourceHeight) {
        if(this.width!=width||this.height!=height||this.sourceWidth!=sourceWidth||this.sourceHeight!=sourceHeight)cancel();
        this.width=width;this.height=height;this.sourceWidth=sourceWidth;this.sourceHeight=sourceHeight;
    }
    /** Mask bits are left/middle/right, mapped to X11 buttons 1/2/3. */
    public boolean pointer(float x,float y,int next) {
        if(width<=0||height<=0||sourceWidth<=0||sourceHeight<=0||!Float.isFinite(x)||!Float.isFinite(y)){cancel();return false;}
        sink.move(Math.max(0,Math.min(sourceWidth-1,x*sourceWidth/width)),Math.max(0,Math.min(sourceHeight-1,y*sourceHeight/height)));
        moves++;buttons(next&7);return true;
    }
    private void buttons(int next) {
        for(int i=0;i<3;i++)if((buttons&(1<<i))!=0&&(next&(1<<i))==0){sink.button(i+1,false);releases++;}
        for(int i=0;i<3;i++)if((buttons&(1<<i))==0&&(next&(1<<i))!=0){sink.button(i+1,true);presses++;}
        buttons=next;
    }
    public void rightButton(boolean down){buttons(down?buttons|4:buttons&~4);}
    public void scroll(float x,float y) {
        if(!Float.isFinite(x)||!Float.isFinite(y)||(x==0&&y==0))return;
        sink.scroll(x,y);scrolls++;
    }
    public void cancel(){buttons(0);}
    public long moves(){return moves;}
    public long presses(){return presses;}
    public long releases(){return releases;}
    public long scrolls(){return scrolls;}
    public int held(){return buttons;}
}
