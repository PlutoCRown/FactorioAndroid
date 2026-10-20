package org.factorio.local.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Native approximation of the official site's light-top/dark-bottom metal frames. */
final class FactorioSurface extends Drawable {
    private final Paint paint=new Paint();
    private final int fill,light,shadow;
    private final float edge;
    private int alpha=255;
    FactorioSurface(int fill,int light,int shadow,float edge) {
        this.fill=fill;this.light=light;this.shadow=shadow;this.edge=Math.max(1,edge);
    }
    private void rect(Canvas c,float left,float top,float right,float bottom,int color) {
        paint.setColor(color);paint.setAlpha(Color.alpha(color)*alpha/255);c.drawRect(left,top,right,bottom,paint);
    }
    @Override public void draw(Canvas c) {
        Rect b=getBounds();float e=Math.min(edge,Math.min(b.width(),b.height())/4f);
        rect(c,b.left,b.top,b.right,b.bottom,0xff201815);
        rect(c,b.left+e/2,b.top+e/2,b.right-e/2,b.bottom-e/2,fill);
        rect(c,b.left+e/2,b.top+e/2,b.right-e/2,b.top+e,light);
        rect(c,b.left+e/2,b.top+e,b.left+e,b.bottom-e/2,light);
        rect(c,b.right-e,b.top+e,b.right-e/2,b.bottom-e/2,shadow);
        rect(c,b.left+e/2,b.bottom-e,b.right-e/2,b.bottom-e/2,shadow);
    }
    @Override public void setAlpha(int value) {alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter) {paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity() {return PixelFormat.TRANSLUCENT;}
}
