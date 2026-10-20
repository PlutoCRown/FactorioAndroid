package com.termux.x11;
import java.util.ArrayList;
import java.util.List;

public final class TouchMouseStateTests {
    private static int passed;
    private static final class Events implements TouchMouseState.Sink {
        final List<String> calls=new ArrayList<>();boolean held;int taps;
        public void move(float x,float y){calls.add("move:"+x+","+y);}
        public void leftButton(boolean down){held=down;calls.add(down?"down":"up");}
        public void tap(float x,float y){taps++;calls.add("tap");}
    }
    private static final class Case {
        final Events events=new Events();final TouchMouseState state=new TouchMouseState(events,8,500);
        Case(){state.viewport(100,50,1280,720);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void check(String label,Runnable body){body.run();passed++;System.out.println("PASS "+label);}
    public static void main(String[] arguments) {
        check("tap presses at DOWN and releases before performClick",()->{
            Case c=new Case();c.state.down(7,200,150,100);
            require(c.state.held()&&c.events.held,"DOWN must hold immediately");
            require(c.events.calls.get(0).equals("move:100.0,100.0")&&c.events.calls.get(1).equals("down"),"position before press");
            c.state.up(7,200,150,150);
            require(c.events.calls.get(3).equals("up")&&c.events.calls.get(4).equals("tap"),"release then tap");
            require(c.state.clicks()==1&&!c.state.held(),"one tap released");
        });
        check("drag streams moves with button held",()->{
            Case c=new Case();c.state.down(3,200,150,0);c.state.move(3,250,160);c.state.move(3,300,170);
            require(c.state.held()&&c.events.held&&c.state.drags()==1,"drag remains held");
            c.state.up(3,350,180,100);require(c.state.moves()==3&&!c.state.held()&&c.events.taps==0,"final move then release, no click");
        });
        check("cancel releases once and ignores further moves",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.cancel();int count=c.events.calls.size();
            c.state.cancel();c.state.move(1,250,160);c.state.up(1,250,160,20);
            require(c.events.calls.size()==count&&c.state.releases()==1&&c.state.cancels()==1&&!c.events.held,"cancel idempotent");
        });
        check("multitouch cancellation cannot resume on remaining finger",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.cancel();c.state.move(1,250,150);c.state.up(1,250,150,20);
            require(c.state.activePointer()==-1&&!c.state.held()&&c.state.clicks()==0,"only fresh DOWN restarts");
            c.state.down(8,300,250,30);c.state.up(8,300,250,60);require(c.state.clicks()==1,"fresh gesture works");
        });
        check("letterbox DOWN never becomes a click on entering viewport",()->{
            Case c=new Case();c.state.down(0,99,100,0);c.state.move(0,200,100);c.state.up(0,200,100,10);
            require(c.events.calls.isEmpty(),"outside down ignored for whole gesture");
        });
        check("out of bounds drag clamps and UP releases",()->{
            Case c=new Case();c.state.down(5,200,150,0);c.state.move(5,-100,-100);
            require(c.state.lastX()==0&&c.state.lastY()==0&&c.events.held,"negative edge clamped");
            c.state.up(5,5000,5000,100);
            require(c.state.lastX()==1279&&c.state.lastY()==719&&!c.events.held&&c.events.taps==0,"far edge clamped and released");
        });
        check("pointer identity mismatch cancels rather than leaving held",()->{
            Case c=new Case();c.state.down(10,200,150,0);c.state.move(11,250,150);
            require(!c.state.held()&&!c.events.held&&c.state.cancels()==1,"unexpected finger released");
        });
        check("long press still has matching release without tap",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.up(1,200,150,1000);
            require(c.state.presses()==1&&c.state.releases()==1&&c.events.taps==0,"hold delivered without tap accounting");
        });
        check("viewport loss releases owned mouse button",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.viewport(0,0,0,0);
            require(!c.events.held&&c.state.activePointer()==-1,"invalid viewport cancels");
        });
        check("replacement DOWN releases previous gesture first",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.down(2,300,150,10);
            require(c.events.calls.get(2).equals("up")&&c.state.activePointer()==2&&c.state.presses()==2,"old press cannot leak");
            c.state.cancel();require(c.state.presses()==c.state.releases(),"balanced presses");
        });
        check("missing MOVE still classifies final UP displacement as drag",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.up(1,400,150,100);
            require(c.state.drags()==1&&c.state.clicks()==0&&!c.events.held,"UP delta prevents false tap");
        });
        check("nonfinite coordinates cancel without sending invalid position",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.move(1,Float.NaN,100);
            require(!c.events.held&&c.state.cancels()==1&&c.state.moves()==0,"invalid move safely released");
        });
        check("native tablet geometry preserves full 3:2 screen",()->{
            RenderSize r=new RenderSize(3408,2272,100);
            require(r.width==3408&&r.height==2272,"native dimensions without 16:9 letterbox");
        });
        check("half tablet geometry scales each axis",()->{
            RenderSize r=new RenderSize(3408,2272,50);
            require(r.width==1704&&r.height==1136,"half width and height");
            require((long)r.width*2272==(long)r.height*3408,"aspect unchanged");
        });
        check("three quarter geometry and even small sizes",()->{
            RenderSize r=new RenderSize(3408,2272,75);
            require(r.width==2556&&r.height==1704,"75 percent dimensions");
            RenderSize tiny=new RenderSize(3,3,50);require(tiny.width==2&&tiny.height==2,"nonzero even pixels");
        });
        check("invalid scale and bounds are rejected",()->{
            int errors=0;
            try{new RenderSize(3408,2272,25);}catch(IllegalArgumentException e){errors++;}
            try{new RenderSize(0,2272,100);}catch(IllegalArgumentException e){errors++;}
            try{new RenderSize(17000,2272,100);}catch(IllegalArgumentException e){errors++;}
            require(errors==3,"invalid dimensions cannot reach native window");
        });
        check("native resolution touch reaches all screen edges",()->{
            Events e=new Events();TouchMouseState t=new TouchMouseState(e,8,500);
            t.sourceSize(3408,2272);t.viewport(0,0,3408,2272);
            t.down(1,1704,1136,0);require(t.lastX()==1704&&t.lastY()==1136,"center maps one to one");
            t.up(1,3407,2271,100);require(t.lastX()==3407&&t.lastY()==2271&&!e.held,"lower right reachable");
        });
        check("half resolution drag scales coordinates without black bars",()->{
            Events e=new Events();TouchMouseState t=new TouchMouseState(e,8,500);
            t.sourceSize(1704,1136);t.viewport(0,0,3408,2272);
            t.down(1,0,0,0);require(t.held(),"top left of complete Surface is clickable");
            t.move(1,1704,1136);require(t.lastX()==852&&t.lastY()==568&&e.held,"drag remains held at scaled center");
            t.up(1,9999,9999,100);require(t.lastX()==1703&&t.lastY()==1135&&!e.held,"scaled edge clamp and release");
        });
        check("resolution change releases old gesture",()->{
            Case c=new Case();c.state.down(1,200,150,0);c.state.sourceSize(3408,2272);
            require(!c.events.held&&c.state.activePointer()==-1&&c.state.presses()==c.state.releases(),"resize cannot leave mouse held");
        });
        System.out.println("TouchMouseStateTests: "+passed+" passed");
    }
}
