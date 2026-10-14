package com.alouncher.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orden de reproducción de una lista, con las reglas de Voltea:
 *  - Sin Aleatorio: siguiente = el de abajo. Al terminar el último solo vuelve al primero si "Repetir" está activo;
 *    si no, la lista se detiene. Con los botones siguiente/anterior siempre se da la vuelta.
 *  - Aleatorio: orden barajado SIN repetir hasta que hayan sonado todos. Al agotarse, con "Repetir" se baraja de
 *    nuevo evitando que el primero sea el que acaba de sonar; sin "Repetir" se detiene.
 * El barajado en curso vive solo en memoria (por lista).
 */
final class PlaylistNav {
    private PlaylistNav() {}

    private static final class State {
        final List<String> queue = new ArrayList<>();
        final List<String> history = new ArrayList<>();
        boolean started;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    /** Olvida el barajado (al cambiar la lista, su orden o el modo Aleatorio). */
    static synchronized void reset(String listKey) {
        STATES.remove(listKey);
    }

    private static State state(String key) {
        State s = STATES.get(key);
        if (s == null) {
            s = new State();
            STATES.put(key, s);
        }
        return s;
    }

    /** @param auto true = el video terminó solo; false = el usuario pulsó "siguiente". Null = no hay siguiente (se detiene). */
    static synchronized BackgroundStore.Item next(String key, BackgroundStore.Playlist p, boolean auto) {
        int n = p.items.size();
        if (n == 0) return null;

        if (!p.shuffle) {
            int ni = p.indexOf(p.cur) + 1;
            if (ni >= n) {
                if (auto && !p.repeat) return null;
                ni = 0;
            }
            return p.items.get(ni);
        }

        if (n == 1) return (auto && !p.repeat) ? null : p.items.get(0);

        State s = state(key);
        s.queue.removeIf(id -> p.indexOf(id) < 0);
        s.history.removeIf(id -> p.indexOf(id) < 0);
        if (!s.started) {
            fill(s, p, false);
            s.started = true;
        }
        if (s.queue.isEmpty()) {
            if (auto && !p.repeat) return null; // ya sonaron todos
            fill(s, p, true);                   // otra vuelta, sin que abra con el que acaba de sonar
        }
        String id = s.queue.remove(0);
        if (!p.cur.isEmpty()) s.history.add(p.cur);
        return p.items.get(p.indexOf(id));
    }

    /** Anterior. Con Aleatorio vuelve al último que sonó; null = no hay anterior. */
    static synchronized BackgroundStore.Item prev(String key, BackgroundStore.Playlist p) {
        int n = p.items.size();
        if (n == 0) return null;

        if (!p.shuffle) {
            int pi = p.indexOf(p.cur) - 1;
            if (pi < 0) pi = n - 1;
            return p.items.get(pi);
        }

        State s = state(key);
        s.history.removeIf(id -> p.indexOf(id) < 0);
        if (s.history.isEmpty()) return null;
        String id = s.history.remove(s.history.size() - 1);
        if (!p.cur.isEmpty() && !s.queue.contains(p.cur)) s.queue.add(0, p.cur);
        return p.items.get(p.indexOf(id));
    }

    /** El usuario eligió ese elemento a mano: cuenta como ya sonado dentro del barajado. */
    static synchronized void picked(String key, BackgroundStore.Playlist p, String newId) {
        if (!p.shuffle) return;
        State s = state(key);
        s.queue.remove(newId);
        if (!p.cur.isEmpty() && !p.cur.equals(newId)) s.history.add(p.cur);
    }

    private static void fill(State s, BackgroundStore.Playlist p, boolean includeCurrent) {
        s.queue.clear();
        for (BackgroundStore.Item it : p.items) {
            if (includeCurrent || !it.id.equals(p.cur)) s.queue.add(it.id);
        }
        Collections.shuffle(s.queue);
        if (includeCurrent && s.queue.size() > 1 && s.queue.get(0).equals(p.cur)) {
            Collections.swap(s.queue, 0, s.queue.size() - 1);
        }
    }
}
