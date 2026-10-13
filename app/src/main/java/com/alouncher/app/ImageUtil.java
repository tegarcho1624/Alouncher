package com.alouncher.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodificación de imágenes reducida (nunca una foto completa en memoria) y miniaturas con caché. */
final class ImageUtil {
    private ImageUtil() {}

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(80);
    private static final ExecutorService THUMB_POOL = Executors.newFixedThreadPool(2);

    /** Decodifica reduciendo lo necesario para que siga cubriendo reqW × reqH. Null si no se puede leer. */
    static Bitmap decodeSampled(File f, int reqW, int reqH) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;

            int rot = exifRotation(path);
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp = r;
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int exifRotation(String path) {
        try {
            int ori = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Carga una miniatura en segundo plano (con caché) sin bloquear la lista. */
    static void loadThumb(File f, ImageView iv, int px) {
        final String key = f.getAbsolutePath() + "@" + px;
        iv.setTag(key);
        Bitmap cached = THUMBS.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        THUMB_POOL.execute(() -> {
            Bitmap bm = decodeSampled(f, px, px);
            if (bm != null) THUMBS.put(key, bm);
            iv.post(() -> {
                if (bm != null && key.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
        });
    }
}
