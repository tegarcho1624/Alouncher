package com.alouncher.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.recyclerview.widget.RecyclerView;

/** Hoja del cajón: se cierra deslizando hacia abajo (desde el encabezado o desde lo alto de la lista). */
public class DrawerSheet extends CappedLinearLayout {

    interface Callback {
        void onPull(float fraction);   // 0 = en su sitio, 1 = totalmente abajo
        void onDismiss();
    }

    private final int slop;
    private RecyclerView grid;
    private Callback callback;
    private float downX, downY;
    private boolean startedOnGrid;
    private boolean pulling;

    public DrawerSheet(Context c, AttributeSet a) {
        super(c, a);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void bind(RecyclerView grid, Callback callback) {
        this.grid = grid;
        this.callback = callback;
    }

    private boolean shouldPull(MotionEvent ev) {
        float dy = ev.getRawY() - downY;
        float dx = ev.getRawX() - downX;
        boolean listAtTop = grid == null || !startedOnGrid || !grid.canScrollVertically(-1);
        return dy > slop && dy > Math.abs(dx) && listAtTop;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                startedOnGrid = grid != null && ev.getY() >= grid.getTop();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pulling && shouldPull(ev)) {
                    pulling = true;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                startedOnGrid = grid != null && ev.getY() >= grid.getTop();
                pulling = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!pulling && shouldPull(ev)) pulling = true;
                if (pulling) {
                    float dy = Math.max(0f, ev.getRawY() - downY - slop);
                    setTranslationY(dy);
                    if (callback != null && getHeight() > 0) {
                        callback.onPull(Math.min(1f, dy / getHeight()));
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    pulling = false;
                    boolean dismiss = ev.getActionMasked() == MotionEvent.ACTION_UP
                            && getTranslationY() > getHeight() * 0.2f;
                    if (dismiss) {
                        if (callback != null) callback.onDismiss();
                    } else {
                        animate().translationY(0f).setDuration(200).start();
                        if (callback != null) callback.onPull(0f);
                    }
                }
                return true;
            default:
                return true;
        }
    }
}
