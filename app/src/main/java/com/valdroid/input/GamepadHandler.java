package com.valdroid.input;

import android.app.Activity;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.valdroid.controls.InputControlsView;

/** Android input adapter for the configurable physical-controller profile. */
public class GamepadHandler {
    private static final int[] LEFT_TRIGGER_AXES = {
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GENERIC_1, MotionEvent.AXIS_GENERIC_3 };
    private static final int[] RIGHT_TRIGGER_AXES = {
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_GENERIC_2, MotionEvent.AXIS_GENERIC_4 };

    private final Activity activity;
    private final InputControlsView controls;
    private final GamepadRouter.Sink sink;
    private GamepadRouter router;
    private boolean running;
    private long lastFrameNs;

    public GamepadHandler(Activity activity, InputControlsView controls) {
        this.activity = activity;
        this.controls = controls;
        this.sink = new GamepadRouter.Sink() {
            @Override public void inject(Binding binding, boolean down) {
                controls.inject(binding, down);
            }
            @Override public void moveCursor(float dx, float dy) {
                controls.moveCursorBy(dx, dy);
            }
            @Override public void gamepadButton(int code, boolean down) {
                try { VirtualGamepad.button(code, down); }
                catch (UnsatisfiedLinkError ignored) { }
            }
            @Override public void gamepadAxis(int code, int value) {
                try { VirtualGamepad.axis(code, value); }
                catch (UnsatisfiedLinkError ignored) { }
            }
            @Override public void syncGamepad() {
                try { VirtualGamepad.sync(); }
                catch (UnsatisfiedLinkError ignored) { }
            }
        };
    }

    /** Resume with the latest saved profile and physical-button calibration. UI thread only. */
    public void start() {
        if (running) return;
        GamepadMapping.load(activity);
        GamepadProfile profile = GamepadProfileStore.load(activity);
        controls.setControllerMouse(profile.usesMouse());
        router = new GamepadRouter(profile, sink);
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Release keys, buttons and axes on pause, loss of focus, or profile replacement. */
    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (router != null) router.releaseAll();
        lastFrameNs = 0;
    }

    /** Release only the removed controller; another controller may own the same output. */
    public void onDeviceRemoved(int deviceId) {
        if (router != null) router.removeDevice(deviceId);
    }

    public boolean onKey(KeyEvent event) {
        if (!isControllerKey(event)) return false;
        // A stopped activity must not send new holds or pass these buttons to keyboard/Back.
        if (!running || event.getRepeatCount() > 0) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
            return true;
        GamepadProfile.Input input = inputForKey(event.getKeyCode());
        if (input == GamepadProfile.Input.LT || input == GamepadProfile.Input.RT)
            router.triggerCapabilities(event.getDeviceId(),
                    hasAnyAxis(event.getDevice(), event.getSource(), LEFT_TRIGGER_AXES),
                    hasAnyAxis(event.getDevice(), event.getSource(), RIGHT_TRIGGER_AXES));
        if (input != null)
            router.key(event.getDeviceId(), event.getKeyCode(), input,
                    event.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }

    public boolean onMotion(MotionEvent event) {
        if (!isFromGamepad(event.getSource()) || event.getAction() != MotionEvent.ACTION_MOVE)
            return false;
        if (!running) return true;
        int rightX = MotionEvent.AXIS_Z, rightY = MotionEvent.AXIS_RZ;
        if (!hasAxis(event, rightX) && hasAxis(event, MotionEvent.AXIS_RX)) {
            rightX = MotionEvent.AXIS_RX;
            rightY = MotionEvent.AXIS_RY;
        }
        router.motion(event.getDeviceId(),
                event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(rightX), event.getAxisValue(rightY),
                readTrigger(event, LEFT_TRIGGER_AXES), readTrigger(event, RIGHT_TRIGGER_AXES),
                event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                hasAnyAxis(event, LEFT_TRIGGER_AXES), hasAnyAxis(event, RIGHT_TRIGGER_AXES));
        return true;
    }

    /** Mixed keyboard/controller devices retain their ordinary keyboard key behavior. */
    public static boolean isControllerKey(KeyEvent event) {
        return isControllerKey(event.getSource(), event.getKeyCode());
    }

    public static boolean isControllerKey(int source, int code) {
        if (!isFromGamepad(source)) return false;
        return inputForKey(code) != null
                || (code >= KeyEvent.KEYCODE_BUTTON_A && code <= KeyEvent.KEYCODE_BUTTON_MODE)
                || (code >= KeyEvent.KEYCODE_BUTTON_1 && code <= KeyEvent.KEYCODE_BUTTON_16)
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private static GamepadProfile.Input inputForKey(int code) {
        // Calibration takes priority even for unusual buttons such as Android Back.
        switch (GamepadMapping.toLogical(code)) {
            case GamepadMapping.L_A: return GamepadProfile.Input.A;
            case GamepadMapping.L_B: return GamepadProfile.Input.B;
            case GamepadMapping.L_X: return GamepadProfile.Input.X;
            case GamepadMapping.L_Y: return GamepadProfile.Input.Y;
            case GamepadMapping.L_LB: return GamepadProfile.Input.LB;
            case GamepadMapping.L_RB: return GamepadProfile.Input.RB;
            case GamepadMapping.L_SELECT: return GamepadProfile.Input.SELECT;
            case GamepadMapping.L_START: return GamepadProfile.Input.START;
            case GamepadMapping.L_GUIDE: return GamepadProfile.Input.GUIDE;
            case GamepadMapping.L_L3: return GamepadProfile.Input.L3;
            case GamepadMapping.L_R3: return GamepadProfile.Input.R3;
            default: break;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return GamepadProfile.Input.DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return GamepadProfile.Input.DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return GamepadProfile.Input.DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return GamepadProfile.Input.DPAD_LEFT;
            case KeyEvent.KEYCODE_BUTTON_L2: return GamepadProfile.Input.LT;
            case KeyEvent.KEYCODE_BUTTON_R2: return GamepadProfile.Input.RT;
            case KeyEvent.KEYCODE_BACK: return GamepadProfile.Input.B;
            default: return null;
        }
    }

    private static boolean hasAxis(MotionEvent event, int axis) {
        return hasAxis(event.getDevice(), event.getSource(), axis);
    }

    private static boolean hasAxis(InputDevice device, int source, int axis) {
        return device != null && (device.getMotionRange(axis, source) != null
                || device.getMotionRange(axis) != null);
    }

    private static boolean hasAnyAxis(MotionEvent event, int[] axes) {
        return hasAnyAxis(event.getDevice(), event.getSource(), axes);
    }

    private static boolean hasAnyAxis(InputDevice device, int source, int[] axes) {
        for (int axis : axes) if (hasAxis(device, source, axis)) return true;
        return false;
    }

    private static float readTrigger(MotionEvent event, int[] axes) {
        InputDevice device = event.getDevice();
        float value = 0;
        for (int axis : axes) {
            InputDevice.MotionRange range = device == null ? null
                    : device.getMotionRange(axis, event.getSource());
            if (range == null && device != null) range = device.getMotionRange(axis);
            if (device != null && range == null) continue;
            float sample = event.getAxisValue(axis);
            if (range != null && range.getMin() < 0 && range.getRange() > 0)
                sample = (sample - range.getMin()) / range.getRange();
            value = Math.max(value, Math.max(0, Math.min(1, sample)));
        }
        return value;
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = lastFrameNs == 0 ? 1f / 60f : (frameTimeNanos - lastFrameNs) / 1e9f;
            lastFrameNs = frameTimeNanos;
            router.frame(Math.max(0, Math.min(0.1f, dt)));
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** Exclude keyboard-only devices and Android TV remotes from controller UI detection. */
    public static boolean hasConnectedGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            try {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.isVirtual()) continue;
                int sources = device.getSources();
                boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                if (gamepad && device.getMotionRanges() != null
                        && !device.getMotionRanges().isEmpty()) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }
}
