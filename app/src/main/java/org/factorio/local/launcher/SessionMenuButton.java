/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.factorio.local.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.ImageButton;

/** Compact icon with the website button bevel; the Activity retains its original 64x48dp hit area. */
public final class SessionMenuButton extends ImageButton {
    public SessionMenuButton(Context context) {
        super(context);
        setBackground(new FactoryButtonDrawable(getResources().getDisplayMetrics().density));
        setBackgroundTintList(null);
        setImageResource(R.drawable.ic_nav_settings);
        setImageTintList(ColorStateList.valueOf(Color.BLACK));
        setScaleType(ScaleType.CENTER_INSIDE);
        setContentDescription(context.getString(R.string.session_menu));
        setTooltipText(context.getString(R.string.session_menu));
        updateAppearance();
    }
    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateAppearance();
    }
    private void updateAppearance() {
        int padding=dp(12), shift=isPressed()?dp(2):0;
        setPadding(padding,padding+shift,padding,padding-shift);
        setAlpha(isPressed()||isHovered()||isFocused()?0.80f:0.45f);
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
}
