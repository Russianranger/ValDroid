package com.valdroid.input;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One writer owns the virtual pad. Hidden touch controls cannot cancel physical input. */
public final class GamepadOutputArbiter {
    public interface Sink {
        void button(int code, boolean down);
        void axis(int code, int value);
        void sync();
    }

    private final Sink sink;
    private final Set<Integer> pressed = new HashSet<>();
    private final Map<Integer, Integer> axes = new HashMap<>();
    private boolean physicalConnected;
    private boolean dirty;

    public GamepadOutputArbiter(Sink sink) { this.sink = sink; }

    public synchronized void setPhysicalConnected(boolean connected) {
        if (physicalConnected == connected) return;
        // Neutralize the previous owner before accepting a new source, including pending taps.
        for (int code : pressed) { sink.button(code, false); dirty = true; }
        for (Map.Entry<Integer, Integer> axis : axes.entrySet()) {
            if (axis.getValue() != 0) { sink.axis(axis.getKey(), 0); dirty = true; }
        }
        flush();
        pressed.clear();
        axes.clear();
        physicalConnected = connected;
    }

    public synchronized void button(boolean physical, int code, boolean down) {
        if (physical != physicalConnected) return;
        boolean changed = down ? pressed.add(code) : pressed.remove(code);
        if (changed) { sink.button(code, down); dirty = true; }
    }

    public synchronized void axis(boolean physical, int code, int value) {
        if (physical != physicalConnected) return;
        int previous = axes.containsKey(code) ? axes.get(code) : 0;
        if (previous == value) return;
        axes.put(code, value);
        sink.axis(code, value);
        dirty = true;
    }

    public synchronized void sync(boolean physical) {
        if (physical == physicalConnected) flush();
    }

    private void flush() {
        if (dirty) sink.sync();
        dirty = false;
    }
}
