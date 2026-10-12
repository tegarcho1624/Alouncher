package com.alouncher.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.InputStream;

/** Decodifica la imagen elegida como ícono. Devuelve null si ya no existe o no se puede leer. */
final class IconLoader {
    private IconLoader() {}

    static Drawable load(Context ctx, String uriString, int px) {
        try {
            Uri uri = Uri.parse(uriString);
            ContentResolver cr = ctx.getContentResolver();

            // 1) medir sin cargar en memoria
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            // 2) decodificar reducida (nunca una foto de 12 MP completa)
            int sample = 1;
            while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) return null;

            // 3) orientación EXIF (fotos de cámara)
            int rot = 0;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in != null) {
                    int ori = new ExifInterface(in).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
                }
            } catch (Exception ignored) {
            }

            // 4) recorte cuadrado centrado + tamaño final
            int side = Math.min(bmp.getWidth(), bmp.getHeight());
            Matrix m = null;
            if (rot != 0) {
                m = new Matrix();
                m.postRotate(rot);
            }
            Bitmap square = Bitmap.createBitmap(bmp,
                    (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side, m, true);
            Bitmap scaled = Bitmap.createScaledBitmap(square, px, px, true);

            // 5) esquinas redondeadas para que combine con los íconos adaptativos
            RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(ctx.getResources(), scaled);
            d.setAntiAlias(true);
            d.setCornerRadius(px * 0.22f);
            return d;
        } catch (Exception | OutOfMemoryError e) {
            return null; // archivo movido/borrado, permiso perdido, formato raro...
        }
    }
}
