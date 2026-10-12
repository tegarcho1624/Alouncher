package com.alouncher.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** LinearLayout con ancho máximo (para que dock y cajón no se estiren en toda la tablet). */
public class CappedLinearLayout extends LinearLayout {
    private int maxWidth = Integer.MAX_VALUE;

    public CappedLinearLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setMaxWidth(int px) {
        maxWidth = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec);
        int size = MeasureSpec.getSize(widthSpec);
        if (mode != MeasureSpec.UNSPECIFIED && size > maxWidth) {
            widthSpec = MeasureSpec.makeMeasureSpec(maxWidth, mode);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
