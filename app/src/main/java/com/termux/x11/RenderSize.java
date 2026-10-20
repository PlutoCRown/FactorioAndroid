package com.termux.x11;

/** Pixel dimensions for the complete game Surface; percentage applies to each axis. */
public final class RenderSize {
    public final int width,height,scalePercent;
    public RenderSize(int surfaceWidth,int surfaceHeight,int scalePercent) {
        if(surfaceWidth<2||surfaceHeight<2||surfaceWidth>16384||surfaceHeight>16384)
            throw new IllegalArgumentException("Invalid game Surface dimensions");
        if(scalePercent!=50&&scalePercent!=75&&scalePercent!=100)
            throw new IllegalArgumentException("Unsupported render scale");
        this.scalePercent=scalePercent;
        width=Math.max(2,(surfaceWidth*scalePercent/100)&~1);
        height=Math.max(2,(surfaceHeight*scalePercent/100)&~1);
    }
}
