package com.valdroid.input;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static com.valdroid.input.GamepadProfile.Input;
import static com.valdroid.input.GamepadProfile.StickMode;

/**
 * Platform-independent controller routing. Physical keys, hat axes, analog triggers and multiple
 * controllers are collected before diffing the desired output. Releasing one source cannot release
 * a target still held by another source. Remaps do not also send the original gamepad action.
 */
public final class GamepadRouter {
    public interface Sink {
        void inject(Binding binding, boolean down);
        void moveCursor(float dx, float dy);
        void gamepadButton(int code, boolean down);
        void gamepadAxis(int code, int value);
        void syncGamepad();
    }

    private static final float DEADZONE = 0.15f;
    private static final float DIRECTION_THRESHOLD = 0.5f;
    private static final float TRIGGER_THRESHOLD = 0.3f;
    private static final float CURSOR_SPEED = 1400f;
    private static final float SCROLL_INTERVAL = 0.09f;
    private static final Input[] INPUTS = Input.values();
    private static final int[] AXES = { VirtualGamepad.ABS_X, VirtualGamepad.ABS_Y,
            VirtualGamepad.ABS_RX, VirtualGamepad.ABS_RY, VirtualGamepad.ABS_Z,
            VirtualGamepad.ABS_RZ, VirtualGamepad.ABS_HAT0X, VirtualGamepad.ABS_HAT0Y };

    private static final class DeviceState {
        // Keep physical identities: two buttons may calibrate to the same logical input.
        final Map<Integer, Input> keys = new HashMap<>();
        float lx, ly, rx, ry, lt, rt, hatX, hatY;
        boolean analogLeftTrigger, analogRightTrigger;
        boolean down(Input input) { return keys.containsValue(input); }
    }

    private final GamepadProfile profile;
    private final Sink sink;
    private final Map<Integer, DeviceState> devices = new HashMap<>();
    private final EnumSet<Binding> held = EnumSet.noneOf(Binding.class);
    private final EnumSet<Binding> wanted = EnumSet.noneOf(Binding.class);
    private final EnumMap<Binding, Float> scrollTime = new EnumMap<>(Binding.class);
    private final int[] axes = new int[AXES.length];
    private final int[] previousAxes = new int[AXES.length];
    private float cursorX, cursorY, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
    private int dpadMask;

    public GamepadRouter(GamepadProfile profile, Sink sink) {
        this.profile = profile;
        this.sink = sink;
    }

    public void key(int deviceId, int physicalKey, Input input, boolean down) {
        DeviceState state = device(deviceId);
        if (down) state.keys.put(physicalKey, input);
        else state.keys.remove(physicalKey);
        frame(0); // Preserve taps shorter than a display frame.
    }

    public void key(int deviceId, Input input, boolean down) {
        key(deviceId, input.ordinal(), input, down);
    }

    /** Metadata can arrive with the first key, before the first analog motion report. */
    public void triggerCapabilities(int deviceId, boolean left, boolean right) {
        DeviceState state = device(deviceId);
        state.analogLeftTrigger = left;
        state.analogRightTrigger = right;
    }

    public void motion(int deviceId, float lx, float ly, float rx, float ry,
                       float lt, float rt, float hatX, float hatY) {
        DeviceState state = device(deviceId);
        motion(deviceId, lx, ly, rx, ry, lt, rt, hatX, hatY,
                state.analogLeftTrigger || lt > 0, state.analogRightTrigger || rt > 0);
    }

    public void motion(int deviceId, float lx, float ly, float rx, float ry,
                       float lt, float rt, float hatX, float hatY,
                       boolean analogLeftTrigger, boolean analogRightTrigger) {
        DeviceState state = device(deviceId);
        state.lx = clamp(lx); state.ly = clamp(ly);
        state.rx = clamp(rx); state.ry = clamp(ry);
        state.lt = Math.max(0, clamp(lt)); state.rt = Math.max(0, clamp(rt));
        state.hatX = clamp(hatX); state.hatY = clamp(hatY);
        state.analogLeftTrigger = analogLeftTrigger;
        state.analogRightTrigger = analogRightTrigger;
        frame(0);
    }

    public void removeDevice(int deviceId) {
        devices.remove(deviceId);
        frame(0);
    }

    public void releaseAll() {
        devices.clear();
        frame(0);
        scrollTime.clear();
    }

    private DeviceState device(int id) {
        DeviceState state = devices.get(id);
        if (state == null) { state = new DeviceState(); devices.put(id, state); }
        return state;
    }

    public void frame(float dt) {
        wanted.clear();
        cursorX = cursorY = leftX = leftY = rightX = rightY = leftTrigger = rightTrigger = 0;
        dpadMask = 0;
        for (DeviceState state : devices.values()) {
            for (Input input : INPUTS) {
                if (input.ordinal() >= Input.LS_UP.ordinal()) break;
                float amount = state.down(input) ? 1 : 0;
                float threshold = 0;
                Binding binding = profile.get(input);
                switch (input) {
                    case LT:
                        // Digital trigger aliases must not turn partial analog travel into 100%.
                        // For keys/mouse buttons both reports still combine as a digital hold.
                        amount = binding.kind == Binding.Kind.GP_TRIGGER && state.analogLeftTrigger
                                ? state.lt : Math.max(amount, state.lt);
                        threshold = TRIGGER_THRESHOLD; break;
                    case RT:
                        amount = binding.kind == Binding.Kind.GP_TRIGGER && state.analogRightTrigger
                                ? state.rt : Math.max(amount, state.rt);
                        threshold = TRIGGER_THRESHOLD; break;
                    case DPAD_UP: if (state.hatY < -0.5f) amount = 1; break;
                    case DPAD_RIGHT: if (state.hatX > 0.5f) amount = 1; break;
                    case DPAD_DOWN: if (state.hatY > 0.5f) amount = 1; break;
                    case DPAD_LEFT: if (state.hatX < -0.5f) amount = 1; break;
                    default: break;
                }
                collect(binding, amount, threshold);
            }
            collectStick(profile.leftStick, state.lx, state.ly, true);
            collectStick(profile.rightStick, state.rx, state.ry, false);
        }

        axes[0] = Math.round(clamp(leftX) * VirtualGamepad.STICK_MAX);
        axes[1] = Math.round(clamp(leftY) * VirtualGamepad.STICK_MAX);
        axes[2] = Math.round(clamp(rightX) * VirtualGamepad.STICK_MAX);
        axes[3] = Math.round(clamp(rightY) * VirtualGamepad.STICK_MAX);
        axes[4] = Math.round(leftTrigger * VirtualGamepad.TRIGGER_MAX);
        axes[5] = Math.round(rightTrigger * VirtualGamepad.TRIGGER_MAX);
        axes[6] = ((dpadMask & 2) != 0 ? 1 : 0) - ((dpadMask & 8) != 0 ? 1 : 0);
        axes[7] = ((dpadMask & 4) != 0 ? 1 : 0) - ((dpadMask & 1) != 0 ? 1 : 0);

        boolean gamepadChanged = false;
        for (Binding binding : held) {
            if (!wanted.contains(binding)) {
                if (binding.kind == Binding.Kind.GP_BUTTON) {
                    sink.gamepadButton(binding.code, false); gamepadChanged = true;
                } else sink.inject(binding, false);
                scrollTime.remove(binding);
            }
        }
        for (Binding binding : wanted) {
            if (!held.contains(binding)) {
                if (binding.kind == Binding.Kind.GP_BUTTON) {
                    sink.gamepadButton(binding.code, true); gamepadChanged = true;
                } else sink.inject(binding, true);
                if (binding.kind == Binding.Kind.SCROLL) scrollTime.put(binding, 0f);
            } else if (binding.kind == Binding.Kind.SCROLL && dt > 0) {
                float elapsed = scrollTime.get(binding) + dt;
                if (elapsed >= SCROLL_INTERVAL) {
                    sink.inject(binding, true);
                    elapsed %= SCROLL_INTERVAL;
                }
                scrollTime.put(binding, elapsed);
            }
        }
        held.clear(); held.addAll(wanted);
        for (int i = 0; i < AXES.length; i++) {
            if (axes[i] != previousAxes[i]) {
                sink.gamepadAxis(AXES[i], axes[i]);
                previousAxes[i] = axes[i];
                gamepadChanged = true;
            }
        }
        if (gamepadChanged) sink.syncGamepad();
        if (dt > 0 && (cursorX != 0 || cursorY != 0))
            sink.moveCursor(clamp(cursorX) * CURSOR_SPEED * dt, clamp(cursorY) * CURSOR_SPEED * dt);
    }

    private void collectStick(StickMode mode, float x, float y, boolean left) {
        switch (mode) {
            case GAMEPAD_LEFT:
                leftX = strongest(leftX, x); leftY = strongest(leftY, y); break;
            case GAMEPAD_RIGHT:
                rightX = strongest(rightX, x); rightY = strongest(rightY, y); break;
            case MOUSE:
                cursorX += curve(x); cursorY += curve(y); break;
            case DIRECTIONS:
                collect(profile.get(left ? Input.LS_UP : Input.RS_UP), Math.max(0, -y), DIRECTION_THRESHOLD);
                collect(profile.get(left ? Input.LS_RIGHT : Input.RS_RIGHT), Math.max(0, x), DIRECTION_THRESHOLD);
                collect(profile.get(left ? Input.LS_DOWN : Input.RS_DOWN), Math.max(0, y), DIRECTION_THRESHOLD);
                collect(profile.get(left ? Input.LS_LEFT : Input.RS_LEFT), Math.max(0, -x), DIRECTION_THRESHOLD);
                break;
            case DISABLED: break;
        }
    }

    private void collect(Binding binding, float amount, float threshold) {
        if (binding == null || amount <= 0) return;
        switch (binding.kind) {
            case GP_TRIGGER:
                if (binding.code == VirtualGamepad.ABS_Z) leftTrigger = Math.max(leftTrigger, amount);
                else if (binding.code == VirtualGamepad.ABS_RZ) rightTrigger = Math.max(rightTrigger, amount);
                break;
            case MOUSE_MOVE:
                float speed = curve(amount);
                if (binding.code == 0) cursorY -= speed;
                else if (binding.code == 1) cursorX += speed;
                else if (binding.code == 2) cursorY += speed;
                else if (binding.code == 3) cursorX -= speed;
                break;
            case GP_DPAD:
                if (amount > threshold) dpadMask |= binding.code;
                break;
            case NONE: case GP_STICK: break;
            default:
                if (amount > threshold) wanted.add(binding);
                break;
        }
    }

    private static float strongest(float current, float next) {
        return Math.abs(next) > Math.abs(current) ? next : current;
    }

    private static float clamp(float value) {
        return Float.isNaN(value) ? 0 : Math.max(-1, Math.min(1, value));
    }

    private static float curve(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= DEADZONE) return 0;
        float scaled = (magnitude - DEADZONE) / (1 - DEADZONE);
        return Math.signum(value) * scaled * scaled;
    }
}
