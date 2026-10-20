package org.factorio.local.launcher;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small vector navigation glyphs, with accessible names supplied by the host. */
final class RailIcon extends View {
    private final int kind;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    RailIcon(Context context,int kind) {super(context);this.kind=kind;setFocusable(true);setClickable(true);}
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);
        float density=getResources().getDisplayMetrics().density;canvas.scale(density,density);
        pen.setColor(isSelected()?0xff201815:0xffb6b1ad);pen.setAlpha(isEnabled()?255:65);
        pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.7f);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
        if(kind==0) {
            Path p=new Path();p.moveTo(-10,-1);p.lineTo(0,-10);p.lineTo(10,-1);p.moveTo(-7,-3);p.lineTo(-7,9);p.lineTo(-2,9);
            p.lineTo(-2,3);p.lineTo(3,3);p.lineTo(3,9);p.lineTo(7,9);p.lineTo(7,-3);canvas.drawPath(p,pen);
        } else if(kind==1) {
            for(int i=0;i<3;i++) {
                float y=-7+i*7,knob=i==1?-4:4;canvas.drawLine(-10,y,knob-3,y,pen);canvas.drawLine(knob+3,y,10,y,pen);
                canvas.drawCircle(knob,y,3,pen);
            }
        } else {
            canvas.drawRoundRect(-10,-8,10,6,2,2,pen);canvas.drawLine(-4,10,4,10,pen);canvas.drawLine(0,6,0,10,pen);
            canvas.drawCircle(0,-1,2,pen);
        }
        canvas.restore();
    }
    @Override public void setSelected(boolean selected) {super.setSelected(selected);invalidate();}
    @Override public void setEnabled(boolean enabled) {super.setEnabled(enabled);invalidate();}
}
