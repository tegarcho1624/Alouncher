package com.alouncher.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;

/**
 * Video de fondo: MediaPlayer sobre un TextureView (sin controles, sin toques).
 * Se eligió esto y no VideoView porque VideoView no puede "Rellenar" (recortar) ni "Estirar", y ExoPlayer
 * añadiría una dependencia; MediaPlayer ya es parte de Android.
 *
 * Si la superficie se destruye (la vista se oculta o la app pasa a segundo plano) el reproductor se libera
 * guardando la posición, y al volver continúa desde ahí.
 */
public class VideoLayer extends TextureView implements TextureView.SurfaceTextureListener {

    interface Listener {
        void onEnded();  // terminó solo (únicamente si no está en bucle)
        void onError();  // no se pudo cargar o reproducir
    }

    private Listener listener;
    private File file;
    private String mode = BackgroundStore.MODE_FILL;
    private boolean loop = true;
    private float volume = 0f;
    private boolean wantPlay;
    private boolean ended;
    private boolean failed;
    private boolean prepared;
    private int lastPos;
    private int vw;
    private int vh;
    private MediaPlayer mp;
    private Surface surface;
    private SurfaceTexture st;

    public VideoLayer(Context c) {
        super(c);
        setOpaque(false);
        setSurfaceTextureListener(this);
        setClickable(false);
        setFocusable(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Pone (o actualiza) el video. Si es el mismo archivo no se reinicia: solo cambian modo, bucle y volumen. */
    void setSource(File f, String mode, boolean loop, float volume) {
        boolean same = f != null && f.equals(file);
        this.mode = mode;
        this.loop = loop;
        this.volume = volume;
        if (!same) {
            releasePlayer();
            file = f;
            lastPos = 0;
            ended = false;
            failed = false;
            vw = 0;
            vh = 0;
        } else if (mp != null) {
            try {
                mp.setLooping(loop);
                mp.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
            }
        }
        applyTransform();
        ensurePlayer();
    }

    /** Reproducir / pausar (la Home o el cajón dejan de estar a la vista). */
    void setPlaying(boolean play) {
        wantPlay = play;
        if (mp != null && prepared) {
            try {
                if (play && !ended) {
                    if (!mp.isPlaying()) mp.start();
                } else if (!play && mp.isPlaying()) {
                    mp.pause();
                }
            } catch (IllegalStateException ignored) {
            }
        } else if (play) {
            ensurePlayer();
        }
    }

    /** Vuelve a empezar el video actual (Repetir con un solo video en la lista). */
    void restart() {
        ended = false;
        lastPos = 0;
        if (mp != null && prepared) {
            try {
                mp.seekTo(0);
                if (wantPlay) mp.start();
            } catch (IllegalStateException ignored) {
            }
        } else {
            ensurePlayer();
        }
    }

    void clear() {
        releasePlayer();
        file = null;
        lastPos = 0;
        ended = false;
        failed = false;
    }

    private void ensurePlayer() {
        if (file == null || st == null || mp != null || failed) return;
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            prepared = false;
            surface = new Surface(st);
            p.setSurface(surface);
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(loop);
            p.setVolume(volume, volume);
            p.setOnVideoSizeChangedListener((m, w, h) -> {
                if (m != mp) return;
                vw = w;
                vh = h;
                applyTransform();
            });
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                prepared = true;
                vw = m.getVideoWidth();
                vh = m.getVideoHeight();
                applyTransform();
                try {
                    if (lastPos > 0) m.seekTo(lastPos);
                    if (wantPlay && !ended) m.start();
                } catch (IllegalStateException ignored) {
                }
            });
            p.setOnCompletionListener(m -> {
                if (m != mp || loop) return;
                ended = true;
                if (listener != null) listener.onEnded();
            });
            p.setOnErrorListener((m, what, extra) -> {
                if (m == mp) fail();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            fail();
        }
    }

    private void fail() {
        failed = true;
        releasePlayer();
        if (listener != null) listener.onError();
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                if (prepared && !ended) lastPos = mp.getCurrentPosition();
            } catch (Exception ignored) {
            }
            try {
                mp.setOnPreparedListener(null);
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        prepared = false;
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    /** Rellenar (recorta), Estirar o Centrada (se ve completo), manteniendo la proporción donde corresponde. */
    private void applyTransform() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || vw == 0 || vh == 0) return;
        float va = (float) vw / vh;
        float ra = (float) w / h;
        float sx = 1f;
        float sy = 1f;
        if (BackgroundStore.MODE_CENTER.equals(mode)) {
            if (va > ra) sy = ra / va; else sx = va / ra;
        } else if (!BackgroundStore.MODE_STRETCH.equals(mode)) {
            if (va > ra) sx = va / ra; else sy = ra / va;
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        applyTransform();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
        st = s;
        ensurePlayer();
        applyTransform();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
        applyTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
        releasePlayer(); // guarda la posición; al volver la superficie continúa desde ahí
        st = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture s) { }
}
