package com.termux.x11;
import java.util.ArrayList;
import java.util.List;

public final class TouchMouseStateTests {
    private static int passed;
    private static final class Events implements TouchMouseState.Sink {
        final List<String> calls=new ArrayList<>();boolean held,right;int taps;float wheel,lastWheel;
        public void move(float x,float y){calls.add("move:"+x+","+y);}
        public void leftButton(boolean down){require(!down||!right,"left and right must not overlap");held=down;calls.add(down?"down":"up");}
        public void rightButton(boolean down){require(!down||!held,"right and left must not overlap");right=down;calls.add(down?"right-down":"right-up");}
        public void scroll(float notches){require(!held&&!right,"scroll must not hold any button");wheel+=notches;lastWheel=notches;calls.add("wheel:"+notches);}
        public void tap(float x,float y){require(!held&&!right,"tap accounting follows release");taps++;calls.add("tap");}
    }
    private static final class Case {
        final Events events=new Events();final TouchMouseState state=new TouchMouseState(events,8,500);
        Case(){state.viewport(100,50,1280,720);}
        void scroll(){state.down(1,200,150,0);state.beginScroll(1,200,150,2,300,150);}
    }
    private static boolean close(double actual,double expected){return Math.abs(actual-expected)<0.000001;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void check(String label,Runnable body){body.run();passed++;System.out.println("PASS "+label);}
    public static void main(String[] arguments) {
        check("quick tap defers left press until UP then releases before click",()->{
            Case c=new Case();c.state.down(7,200,150,100);
            require(!c.state.held()&&c.events.calls.size()==1,"pending touch moves only");
            c.state.up(7,200,150,150);
            require(c.events.calls.get(2).equals("down")&&c.events.calls.get(3).equals("up")&&c.events.calls.get(4).equals("tap"),"one balanced click");
            require(c.state.clicks()==1&&!c.state.held(),"one tap released");
        });
        check("drag presses at original position and streams moves held",()->{
            Case c=new Case();c.state.down(3,200,150,0);c.state.move(3,250,160);c.state.move(3,300,170);
            require(c.events.calls.get(1).equals("move:100.0,100.0")&&c.events.calls.get(2).equals("down"),"original anchor pressed before drag move");
            require(c.state.held()&&c.events.held&&c.state.drags()==1,"drag remains held");
            c.state.up(3,350,180,100);require(c.state.moves()==3&&!c.state.held()&&c.events.taps==0,"release without click");
        });
        check("cancel releases once and ignores further moves",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,250,150);c.state.cancel();int count=c.events.calls.size();
            c.state.cancel();c.state.move(1,250,160);c.state.up(1,250,160,20);
            require(c.events.calls.size()==count&&c.state.releases()==1&&c.state.cancels()==1&&!c.events.held,"idempotent cancellation");
        });
        check("cancelled gesture cannot restart on remaining finger",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.cancel();c.state.move(1,250,150);c.state.up(1,250,150,20);
            require(c.state.activePointer()==-1&&!c.state.held()&&c.state.clicks()==0,"fresh DOWN required");
            c.state.down(8,300,250,30);c.state.up(8,300,250,60);require(c.state.clicks()==1,"fresh tap works");
        });
        check("outside DOWN stays ignored when entering viewport",()->{
            Case c=new Case();c.state.down(0,99,100,0);c.state.move(0,200,100);c.state.up(0,200,100,10);
            require(c.events.calls.isEmpty(),"outside whole gesture ignored");
        });
        check("out of bounds drag clamps and releases",()->{
            Case c=new Case();c.state.down(5,200,150,0);c.state.move(5,-100,-100);
            require(c.state.lastX()==0&&c.state.lastY()==0&&c.events.held,"negative clamp");c.state.up(5,5000,5000,100);
            require(c.state.lastX()==1279&&c.state.lastY()==719&&!c.events.held&&c.events.taps==0,"far clamp");
        });
        check("pointer mismatch cancels held drag",()->{
            Case c=new Case();c.state.down(10,200,150,0);c.state.move(10,250,150);c.state.move(11,260,150);
            require(!c.state.held()&&c.state.cancels()==1,"mismatch releases");
        });
        check("stationary timeout holds right without any initial left press",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.longPress(1,499);require(!c.state.held(),"not early");
            c.state.longPress(1,500);c.state.longPress(1,800);
            require(c.events.right&&c.state.rightPresses()==1&&c.state.presses()==0,"one sustained right press");
            c.state.up(1,200,150,1000);require(!c.events.right&&c.state.rightReleases()==1&&c.events.taps==0,"release stops mining");
        });
        check("delayed timer UP never turns a long press into left click",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.up(1,200,150,900);
            require(c.state.rightPresses()==1&&c.state.rightReleases()==1&&c.state.presses()==0&&c.events.taps==0,"timer fallback balanced");
        });
        check("wrong pointer or regressed clock cannot trigger long press",()->{
            Case c=new Case();c.state.down(3,200,150,100);c.state.longPress(4,900);c.state.longPress(3,90);require(!c.state.held(),"invalid timer ignored");
            c.state.cancel();c.state.longPress(3,1000);require(c.state.rightPresses()==0,"cancel invalidates timer");
        });
        check("small jitter permits right hold and subsequent right drag",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,203,152);c.state.longPress(1,500);c.state.move(1,300,200);
            require(c.events.right&&!c.events.held&&c.state.drags()==0,"right stays right after movement");c.state.cancel();require(c.state.rightReleases()==1,"cancel right once");
        });
        check("left drag never upgrades to right when it stops moving",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,250,150);c.state.longPress(1,1000);
            require(c.events.held&&!c.events.right&&c.state.rightPresses()==0,"drag remains left");c.state.cancel();
        });
        check("viewport loss releases right button",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.longPress(1,500);c.state.viewport(0,0,0,0);
            require(!c.events.right&&c.state.activePointer()==-1&&c.state.rightReleases()==1,"viewport cancels mining");
        });
        check("nonzero viewport resize cancels pending gesture",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.viewport(100,50,1000,700);c.state.longPress(1,1000);
            require(c.state.activePointer()==-1&&c.state.rightPresses()==0,"layout changes invalidate timer");
        });
        check("replacement DOWN releases old drag before new pending touch",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,250,150);c.state.down(2,300,150,10);
            require(!c.events.held&&c.state.activePointer()==2&&c.state.presses()==c.state.releases(),"no old press leak");c.state.cancel();
        });
        check("missing MOVE still classifies final UP displacement as drag",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.up(1,400,150,100);
            require(c.state.drags()==1&&c.state.clicks()==0&&c.state.presses()==c.state.releases(),"no false tap");
        });
        check("nonfinite coordinates cancel without native invalid positions",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,Float.NaN,100);
            require(c.state.activePointer()==-1&&c.state.moves()==0,"invalid move ignored");
        });
        check("two fingers start scroll without a left click or long press",()->{
            Case c=new Case();c.scroll();c.state.longPress(1,1000);
            require(c.state.scrolling()&&!c.state.held()&&c.state.presses()==0&&c.state.rightPresses()==0&&c.events.wheel==0,"pending click consumed");
        });
        check("wheel sends fractional movement immediately with reversed direction",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,200,130,2,300,130);
            require(close(c.events.wheel,20.0/192)&&c.state.scrollEvents()==1,"sub-notch movement delivered immediately");
            c.state.scrollMove(1,200,102,2,300,102);require(close(c.events.wheel,0.25),"48 screen units emit a quarter notch");
            c.state.scrollMove(1,200,198,2,300,198);require(close(c.events.wheel,-0.25)&&close(c.state.scrollNotches(),0.75),"opposite movement reverses immediately");
        });
        check("each motion sample sends one immediate fractional event without changing the total distance",()->{
            Case batch=new Case();batch.scroll();batch.state.scrollMove(1,200,54,2,300,54);
            Case sampled=new Case();sampled.scroll();
            for(int y=147;y>=54;y-=3)sampled.state.scrollMove(1,200,y,2,300,y);
            require(close(batch.events.wheel,0.5)&&close(sampled.events.wheel,batch.events.wheel),"sample cadence cannot accelerate scrolling");
            require(batch.state.scrollEvents()==1&&sampled.state.scrollEvents()==32,"one event per nonzero sample, with no synthetic subdivision");
        });
        check("subpixel slow movement reaches the wheel without waiting for a threshold",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,200,149.75f,2,300,149.75f);
            require(close(c.events.wheel,0.25/192)&&c.state.scrollEvents()==1,"first tiny sample forwarded immediately");
            c.state.scrollMove(1,200,150,2,300,150);require(close(c.events.wheel,0),"tiny reversal has no delayed remainder");
        });
        check("horizontal motion and centered pinch have no wheel effect",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,250,150,2,350,150);c.state.scrollMove(1,250,100,2,350,200);
            require(c.events.wheel==0&&c.state.scrollEvents()==0,"centroid stationary");
        });
        check("second finger releases left drag before wheel",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,250,150);c.state.beginScroll(1,250,150,2,350,150);
            c.state.scrollMove(1,250,102,2,350,102);require(!c.events.held&&c.state.presses()==c.state.releases()&&c.events.wheel==0.25f,"left up precedes scroll");
        });
        check("second finger releases right mining before wheel",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.longPress(1,500);c.state.beginScroll(1,200,150,2,300,150);
            c.state.scrollMove(1,200,198,2,300,198);require(!c.events.right&&c.state.rightPresses()==c.state.rightReleases()&&c.events.wheel==-0.25f,"right up precedes scroll");
        });
        check("lifting one scroll finger never clicks or resumes single drag",()->{
            Case c=new Case();c.scroll();c.state.cancel();c.state.move(1,300,200);c.state.up(1,300,200,900);
            require(c.events.taps==0&&c.state.presses()==0&&c.state.rightPresses()==0,"remaining finger blocked until fresh DOWN");
        });
        check("scroll pointer mismatch or nonfinite data cancels",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,200,100,3,300,100);require(!c.state.scrolling(),"wrong pointer cancelled");
            c.scroll();c.state.scrollMove(1,200,Float.POSITIVE_INFINITY,2,300,100);require(!c.state.scrolling()&&c.events.wheel==0,"invalid wheel data ignored");
        });
        check("bad second finger cannot create scroll from ignored DOWN",()->{
            Case c=new Case();c.state.down(1,10,10,0);c.state.beginScroll(1,200,150,2,300,150);require(!c.state.scrolling(),"no initial accepted finger");
            c.state.down(1,200,150,0);c.state.beginScroll(1,200,150,1,300,150);require(!c.state.scrolling(),"duplicate identity rejected");
        });
        check("scroll cadence uses screen distance independent of render scale",()->{
            Case c=new Case();c.state.sourceSize(640,360);c.scroll();c.state.scrollMove(1,200,198,2,300,198);
            require(c.events.wheel==-0.25f&&c.state.lastX()==75&&c.state.lastY()==50,"scaled anchor with unscaled wheel distance");
        });
        check("scroll resize cancels before a fresh gesture",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,200,180,2,300,180);c.state.sourceSize(3408,2272);
            require(!c.state.scrolling()&&!c.state.held(),"resize ends scroll");c.scroll();c.state.scrollMove(1,200,170,2,300,170);require(close(c.events.wheel,-50.0/192),"fresh gesture has no delayed movement");
        });
        check("extreme finite scroll movement cannot flood native input",()->{
            Case c=new Case();c.scroll();c.state.scrollMove(1,200,Float.MAX_VALUE,2,300,Float.MAX_VALUE);
            require(c.events.wheel==-1&&c.state.scrollEvents()==1,"malformed sample emits one bounded event");c.state.scrollMove(1,200,Float.MAX_VALUE,2,300,Float.MAX_VALUE);require(c.events.wheel==-1,"no overflow remainder");
        });
        check("native tablet geometry preserves full screen",()->{
            RenderSize r=new RenderSize(3408,2272,100);require(r.width==3408&&r.height==2272,"native size");
        });
        check("half tablet geometry scales each axis",()->{
            RenderSize r=new RenderSize(3408,2272,50);require(r.width==1704&&r.height==1136,"half size");require((long)r.width*2272==(long)r.height*3408,"same aspect");
        });
        check("three quarter and tiny geometry",()->{
            RenderSize r=new RenderSize(3408,2272,75);require(r.width==2556&&r.height==1704,"75 percent");r=new RenderSize(3,3,50);require(r.width==2&&r.height==2,"small nonzero size");
        });
        check("invalid render scale and bounds rejected",()->{
            int errors=0;try{new RenderSize(3408,2272,25);}catch(IllegalArgumentException e){errors++;}try{new RenderSize(0,2272,100);}catch(IllegalArgumentException e){errors++;}try{new RenderSize(17000,2272,100);}catch(IllegalArgumentException e){errors++;}require(errors==3,"invalid geometry");
        });
        check("native touch reaches all edges",()->{
            Events e=new Events();TouchMouseState t=new TouchMouseState(e,8,500);t.sourceSize(3408,2272);t.viewport(0,0,3408,2272);
            t.down(1,1704,1136,0);require(t.lastX()==1704&&t.lastY()==1136,"native center");t.up(1,3407,2271,100);require(t.lastX()==3407&&t.lastY()==2271&&!e.held,"native corner");
        });
        check("half render resolution drag has no letterbox",()->{
            Events e=new Events();TouchMouseState t=new TouchMouseState(e,8,500);t.sourceSize(1704,1136);t.viewport(0,0,3408,2272);
            t.down(1,0,0,0);require(t.activePointer()==1,"top left accepted");t.move(1,1704,1136);require(t.lastX()==852&&t.lastY()==568&&e.held,"scaled drag");t.up(1,9999,9999,100);require(t.lastX()==1703&&t.lastY()==1135&&!e.held,"clamped release");
        });
        check("resolution change releases held drag",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,300,150);c.state.sourceSize(3408,2272);
            require(!c.events.held&&c.state.activePointer()==-1&&c.state.presses()==c.state.releases(),"no held drag after resize");
        });
        System.out.println("TouchMouseStateTests: "+passed+" passed");
    }
}
