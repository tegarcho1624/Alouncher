package com.alouncher.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * Capa de fondo (se dibuja DETRÁS de los íconos): fondo estático, fondo animado y un velo oscuro encima
 * para mantener la legibilidad (el de Voltea, de .5 a .72). No recibe toques.
 * El fondo animado REEMPLAZA visualmente al estático mientras esté activo; el estático sigue guardado
 * y vuelve a verse en cuanto se quita el animado.
 */
public class BackdropView extends FrameLayout {
    private final ImageView image;
    private final VideoLayer video;
    private final View veil;
    private int radius;
    private boolean hasImage;
    private boolean hasVideo;

    public BackdropView(Context c, AttributeSet a) {
        super(c, a);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        video = new VideoLayer(c);
        addView(video, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        veil = new View(c);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x800E0C0A, 0xB80E0C0A}));
        addView(veil, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        refresh();
    }

    /** Redondea las esquinas de arriba (para el cajón). */
    void setTopCornerRadius(int px) {
        radius = px;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight() + radius, radius);
            }
        });
        setClipToOutline(true);
    }

    // ---- Fondo estático ----------------------------------------------------------

    void showImage(Bitmap bmp, String mode) {
        image.setImageBitmap(bmp);
        if (BackgroundStore.MODE_STRETCH.equals(mode)) {
            image.setScaleType(ImageView.ScaleType.FIT_XY);
        } else if (BackgroundStore.MODE_CENTER.equals(mode)) {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        hasImage = true;
        refresh();
    }

    /** Quita el fondo estático (no toca el animado). */
    void clear() {
        image.setImageDrawable(null);
        hasImage = false;
        refresh();
    }

    // ---- Fondo animado -----------------------------------------------------------

    void showVideo(File f, String mode, boolean loop, float volume, VideoLayer.Listener l) {
        video.setListener(l);
        video.setSource(f, mode, loop, volume);
        hasVideo = true;
        refresh();
    }

    /** Quita el fondo animado: vuelve a verse el estático (o el predeterminado). */
    void clearVideo() {
        video.setListener(null);
        video.clear();
        hasVideo = false;
        refresh();
    }

    void restartVideo() {
        video.restart();
    }

    void setVideoPlaying(boolean play) {
        video.setPlaying(play);
    }

    boolean hasVideo() {
        return hasVideo;
    }

    boolean hasMedia() {
        return hasImage || hasVideo;
    }

    private void refresh() {
        image.setVisibility(hasVideo ? View.INVISIBLE : View.VISIBLE);
        video.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        boolean any = hasImage || hasVideo;
        veil.setVisibility(any ? View.VISIBLE : View.GONE);
        setBackgroundColor(any ? 0xFF0E0C0A : Color.TRANSPARENT); // bandas del modo "Centrada"
    }
}
