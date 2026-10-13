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

/**
 * Capa de fondo (se dibuja DETRÁS de los íconos). Imagen + velo oscuro para mantener la legibilidad
 * (el mismo criterio que Voltea: velo de .5 a .72). No recibe toques.
 * En la siguiente sub-fase aquí mismo vivirá el video del fondo animado.
 */
public class BackdropView extends FrameLayout {
    private final ImageView image;
    private final View veil;
    private int radius;

    public BackdropView(Context c, AttributeSet a) {
        super(c, a);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        veil = new View(c);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x800E0C0A, 0xB80E0C0A}));
        addView(veil, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        clear();
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

    void showImage(Bitmap bmp, String mode) {
        image.setImageBitmap(bmp);
        if (BackgroundStore.MODE_STRETCH.equals(mode)) {
            image.setScaleType(ImageView.ScaleType.FIT_XY);
        } else if (BackgroundStore.MODE_CENTER.equals(mode)) {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        setBackgroundColor(0xFF0E0C0A); // bandas del modo "Centrada"
        veil.setVisibility(View.VISIBLE);
    }

    /** Sin fondo: queda transparente (la Home muestra el fondo del sistema; el cajón, su color). */
    void clear() {
        image.setImageDrawable(null);
        veil.setVisibility(View.GONE);
        setBackgroundColor(Color.TRANSPARENT);
    }
}
