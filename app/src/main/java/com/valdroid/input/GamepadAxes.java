package com.valdroid.input;

import android.view.MotionEvent;

/** Select one source axis for each control; an alias never becomes an additional input. */
public final class GamepadAxes {
    public static final int NONE = -1;

    public static final class Range {
        public final float min, max, flat;
        public Range(float min, float max, float flat) {
            this.min = min; this.max = max; this.flat = flat;
        }
        boolean centered() { return min < 0 && max > 0; }
    }

    public interface Source {
        Range range(int axis);
        float value(int axis);
    }

    private final Source source;
    public final int rightX, rightY, leftTrigger, rightTrigger;

    public GamepadAxes(Source source, GamepadProfile.RightStickAxes preference) {
        this.source = source;
        boolean zr = centeredPair(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ);
        boolean rx = centeredPair(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY);
        if (preference == GamepadProfile.RightStickAxes.Z_RZ
                || (preference == GamepadProfile.RightStickAxes.AUTO && zr)) {
            rightX = exists(MotionEvent.AXIS_Z) && exists(MotionEvent.AXIS_RZ) ? MotionEvent.AXIS_Z : NONE;
            rightY = rightX == NONE ? NONE : MotionEvent.AXIS_RZ;
        } else if (preference == GamepadProfile.RightStickAxes.RX_RY
                || (preference == GamepadProfile.RightStickAxes.AUTO && rx)) {
            rightX = exists(MotionEvent.AXIS_RX) && exists(MotionEvent.AXIS_RY) ? MotionEvent.AXIS_RX : NONE;
            rightY = rightX == NONE ? NONE : MotionEvent.AXIS_RY;
        } else {
            rightX = rightY = NONE;
        }
        leftTrigger = first(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
                unusedTrigger(MotionEvent.AXIS_Z, rightX == MotionEvent.AXIS_RX));
        rightTrigger = first(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
                unusedTrigger(MotionEvent.AXIS_RZ, rightY == MotionEvent.AXIS_RY));
    }

    private boolean exists(int axis) {
        Range range = axis == NONE ? null : source.range(axis);
        return range != null && Float.isFinite(range.min) && Float.isFinite(range.max)
                && range.max > range.min;
    }
    private int unusedTrigger(int axis, boolean unused) {
        // A second centered pair may be a stick alias, not signed triggers. Do not guess.
        return unused && exists(axis) && source.range(axis).min >= 0
                && source.range(axis).max > 0 ? axis : NONE;
    }
    private boolean centeredPair(int x, int y) {
        return exists(x) && exists(y) && source.range(x).centered() && source.range(y).centered();
    }
    private int first(int... axes) {
        for (int axis : axes) if (exists(axis)) return axis;
        return NONE;
    }

    public float stick(int axis) {
        if (!exists(axis)) return 0;
        Range range = source.range(axis);
        if (!range.centered()) return 0;
        float value = source.value(axis);
        if (!Float.isFinite(value)) return 0;
        float scale = value < 0 ? -range.min : range.max;
        float normalized = Math.max(-1, Math.min(1, value / scale));
        // Respect the device's flat region and suppress small idle jitter on gamepad outputs too.
        float deviceFlat = Float.isFinite(range.flat) ? range.flat / scale : 0;
        float flat = Math.max(0.15f, Math.min(0.5f, deviceFlat));
        return Math.abs(normalized) <= flat ? 0 : normalized;
    }

    public float trigger(int axis) {
        if (!exists(axis)) return 0;
        Range range = source.range(axis);
        float value = source.value(axis);
        if (!Float.isFinite(value) || range.max <= range.min) return 0;
        return Math.max(0, Math.min(1, (value - range.min) / (range.max - range.min)));
    }

    public float hat(int axis) {
        if (!exists(axis)) return 0;
        float value = source.value(axis);
        return Float.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0;
    }
}
