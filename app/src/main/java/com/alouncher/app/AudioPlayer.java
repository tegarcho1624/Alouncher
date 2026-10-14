package com.alouncher.app;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.File;

/**
 * Audio de fondo de la Home. MediaPlayer, igual que el fondo animado (mismo motor, mismas precauciones).
 * No decide CUÁNDO suena: eso lo decide MainActivity (solo en la Home y nunca si el video suena);
 * aquí solo se reproduce, se pausa y se recuerda la posición.
 */
final class AudioPlayer {

    interface Host {
        void onPrepared();
        void onEnded();  // terminó solo (únicamente si no está en bucle)
        void onError();
    }

    private final Host host;
    private MediaPlayer mp;
    private File file;
    private boolean loop;
    private float volume = 1f;
    private boolean prepared;
    private boolean wantPlay;
    private boolean ended;
    private boolean failed;
    private int pos;       // última posición conocida (ms)
    private int startPos;  // desde dónde empezar al preparar el archivo actual

    AudioPlayer(Host host) {
        this.host = host;
    }

    boolean hasSource() {
        return file != null;
    }

    /** Pone (o actualiza) el audio. Si es el mismo archivo no se reinicia: solo cambian bucle y volumen. */
    void setSource(File f, boolean loop, float volume, int startPos) {
        boolean same = f != null && f.equals(file);
        this.loop = loop;
        this.volume = volume;
        if (!same) {
            release();
            file = f;
            ended = false;
            failed = false;
            this.startPos = startPos;
            pos = startPos;
        } else if (mp != null) {
            try {
                mp.setLooping(loop);
                mp.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
            }
        }
        ensure();
    }

    void setPlaying(boolean play) {
        wantPlay = play;
        if (mp != null && prepared) {
            try {
                if (play && !ended) {
                    if (!mp.isPlaying()) mp.start();
                } else if (!play && mp.isPlaying()) {
                    pos = mp.getCurrentPosition();
                    mp.pause();
                }
            } catch (IllegalStateException ignored) {
            }
        } else if (play) {
            ensure();
        }
    }

    /** Posición actual (para guardarla al salir de la Home). */
    int position() {
        if (mp != null && prepared && !ended) {
            try {
                return mp.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
        }
        return ended ? 0 : pos;
    }

    /** Vuelve a empezar el audio actual (lista de un solo audio con Repetir). */
    void restart() {
        ended = false;
        pos = 0;
        startPos = 0;
        if (mp != null && prepared) {
            try {
                mp.seekTo(0);
                if (wantPlay) mp.start();
            } catch (IllegalStateException ignored) {
            }
        } else {
            ensure();
        }
    }

    void clear() {
        release();
        file = null;
        pos = 0;
        startPos = 0;
        ended = false;
        failed = false;
    }

    private void ensure() {
        if (file == null || mp != null || failed) return;
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            prepared = false;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(loop);
            p.setVolume(volume, volume);
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                prepared = true;
                try {
                    if (startPos > 0) m.seekTo(startPos);
                    startPos = 0;
                    if (wantPlay && !ended) m.start();
                } catch (IllegalStateException ignored) {
                }
                host.onPrepared();
            });
            p.setOnCompletionListener(m -> {
                if (m != mp || loop) return;
                ended = true;
                pos = 0;
                host.onEnded();
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
        release();
        host.onError();
    }

    void release() {
        if (mp != null) {
            try {
                if (prepared && !ended) pos = mp.getCurrentPosition();
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
    }
}
