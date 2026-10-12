package com.alouncher.app;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * Gesto común a cajón, Home y dock:
 *  1) pulsación larga  -> onLongPress (abre el menú)
 *  2) seguir sosteniendo y mover el dedo -> onDragStart (empieza el arrastre)
 */
final class LongPressDrag {

    interface Listener {
        void onLongPress(View v);
        void onDragStart(View v);
    }

    private final float moveThreshold;
    private final Listener listener;
    private boolean longPressed;
    private float downX, downY;

    LongPressDrag(Context c, Listener listener) {
        this.moveThreshold = ViewConfiguration.get(c).getScaledTouchSlop() * 2f;
        this.listener = listener;
    }

    void attach(View v) {
        v.setOnLongClickListener(view -> {
            longPressed = true;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ViewParent p = view.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true); // que no se desplace la lista
            listener.onLongPress(view);
            return true;
        });
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    longPressed = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (longPressed && Math.hypot(ev.getRawX() - downX, ev.getRawY() - downY) > moveThreshold) {
                        longPressed = false;
                        listener.onDragStart(view);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressed = false;
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
