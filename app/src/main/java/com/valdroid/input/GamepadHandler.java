package com.valdroid.input;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.valdroid.controls.InputControlsView;

/** Android input adapter for the configurable physical-controller profile. */
public class GamepadHandler {
    private static final String TAG = "ValDroid/Input";
    private final SparseArray<DeviceAxes> deviceAxes = new SparseArray<>();
    private GamepadProfile profile;
    private int keyTraceLines, motionTraceLines, keyEdges, padButtons, padAxes, mouseFrames;
    private long nextMotionTrace;

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
                keyEdges++;
                controls.inject(binding, down);
            }
            @Override public void moveCursor(float dx, float dy) {
                mouseFrames++;
                controls.moveCursorBy(dx, dy);
            }
            @Override public void gamepadButton(int code, boolean down) {
                padButtons++;
                try { VirtualGamepad.physicalButton(code, down); }
                catch (UnsatisfiedLinkError ignored) { }
            }
            @Override public void gamepadAxis(int code, int value) {
                padAxes++;
                try { VirtualGamepad.physicalAxis(code, value); }
                catch (UnsatisfiedLinkError ignored) { }
            }
            @Override public void syncGamepad() {
                try { VirtualGamepad.syncPhysical(); }
                catch (UnsatisfiedLinkError ignored) { }
            }
        };
    }

    /** Resume with the latest saved profile and physical-button calibration. UI thread only. */
    public void start() {
        if (running) return;
        GamepadMapping.load(activity);
        profile = GamepadProfileStore.load(activity);
        deviceAxes.clear();
        VirtualGamepad.setPhysicalControllerConnected(hasConnectedGamepad());
        keyTraceLines = motionTraceLines = keyEdges = padButtons = padAxes = mouseFrames = 0;
        nextMotionTrace = 0;
        Log.i(TAG, "controller router v2: touch gamepad blocked while physical connected; profile="
                + profile.serialize().replace('\n', ';'));
        controls.setControllerMouse(profile.usesMouse());
        router = new GamepadRouter(profile, sink);
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Release keys, buttons and axes on pause, loss of focus, or profile replacement. */
    public void stop() {
        boolean wasRunning = running;
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (router != null) router.releaseAll();
        deviceAxes.clear();
        if (wasRunning) Log.i(TAG, "released controller outputs: gamepadButtons=" + padButtons
                + " axes=" + padAxes + " mouseKeyboardEdges=" + keyEdges + " mouseFrames=" + mouseFrames);
        lastFrameNs = 0;
    }

    /** Release only the removed controller; another controller may own the same output. */
    public void onDeviceRemoved(int deviceId) {
        if (router != null) router.removeDevice(deviceId);
        deviceAxes.remove(deviceId);
    }

    public boolean onKey(KeyEvent event) {
        if (!isControllerKey(event)) return false;
        // A stopped activity must not send new holds or pass these buttons to keyboard/Back.
        if (!running || event.getRepeatCount() > 0) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
            return true;
        VirtualGamepad.setPhysicalControllerConnected(true);
        GamepadProfile.Input input = inputForKey(event.getKeyCode());
        DeviceAxes axes = axesFor(event.getDeviceId(), event.getDevice());
        if (input == GamepadProfile.Input.LT || input == GamepadProfile.Input.RT)
            router.triggerCapabilities(event.getDeviceId(), axes.selection.leftTrigger != GamepadAxes.NONE,
                    axes.selection.rightTrigger != GamepadAxes.NONE);
        if (input != null) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            router.key(event.getDeviceId(), event.getKeyCode(), input, down);
            if (keyTraceLines < 512) {
                keyTraceLines++;
                Log.i(TAG, "key device=" + event.getDeviceId() + " source=" + event.getSource()
                        + " code=" + event.getKeyCode() + " down=" + down + " row=" + input
                        + " output=" + profile.get(input).name());
            }
        }
        return true;
    }

    public boolean onMotion(MotionEvent event) {
        if (!isFromGamepad(event.getSource())) return false;
        if (!running) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            onDeviceRemoved(event.getDeviceId());
            return true;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return true;
        VirtualGamepad.setPhysicalControllerConnected(true);
        DeviceAxes device = axesFor(event.getDeviceId(), event.getDevice());
        device.event = event;
        GamepadAxes axes = device.selection;
        float lx = axes.stick(MotionEvent.AXIS_X), ly = axes.stick(MotionEvent.AXIS_Y);
        float rx = axes.stick(axes.rightX), ry = axes.stick(axes.rightY);
        float lt = axes.trigger(axes.leftTrigger), rt = axes.trigger(axes.rightTrigger);
        float hx = axes.hat(MotionEvent.AXIS_HAT_X), hy = axes.hat(MotionEvent.AXIS_HAT_Y);
        router.motion(event.getDeviceId(), lx, ly, rx, ry, lt, rt, hx, hy,
                axes.leftTrigger != GamepadAxes.NONE, axes.rightTrigger != GamepadAxes.NONE);
        long now = SystemClock.uptimeMillis();
        if (motionTraceLines < 64 && now >= nextMotionTrace) {
            nextMotionTrace = now + 300;
            motionTraceLines++;
            Log.i(TAG, "motion device=" + event.getDeviceId() + " source=" + event.getSource()
                    + " raw[x,y,z,rz,rx,ry,lt,rt]=" + device.value(MotionEvent.AXIS_X) + ","
                    + device.value(MotionEvent.AXIS_Y) + "," + device.value(MotionEvent.AXIS_Z) + ","
                    + device.value(MotionEvent.AXIS_RZ) + "," + device.value(MotionEvent.AXIS_RX) + ","
                    + device.value(MotionEvent.AXIS_RY) + "," + device.value(MotionEvent.AXIS_LTRIGGER) + ","
                    + device.value(MotionEvent.AXIS_RTRIGGER) + " selected[lx,ly,rx,ry,lt,rt,hatx,haty]="
                    + lx + "," + ly + "," + rx + "," + ry + "," + lt + "," + rt + "," + hx + "," + hy);
        }
        device.event = null; // MotionEvents are pooled; retain only the device's range metadata.
        return true;
    }

    private DeviceAxes axesFor(int id, InputDevice input) {
        DeviceAxes result = deviceAxes.get(id);
        if (result == null) {
            result = new DeviceAxes(input);
            deviceAxes.put(id, result);
            Log.i(TAG, "device=" + id + " name=" + (input == null ? "unknown" : input.getName())
                    + " rightAxes=" + result.selection.rightX + "/" + result.selection.rightY
                    + " triggerAxes=" + result.selection.leftTrigger + "/" + result.selection.rightTrigger
                    + " ranges=" + result.description);
        }
        return result;
    }

    private final class DeviceAxes implements GamepadAxes.Source {
        final GamepadAxes.Range[] ranges = new GamepadAxes.Range[64];
        final GamepadAxes selection;
        final String description;
        MotionEvent event;
        DeviceAxes(InputDevice device) {
            StringBuilder summary = new StringBuilder();
            if (device != null) for (InputDevice.MotionRange range : device.getMotionRanges()) {
                int axis = range.getAxis();
                if (axis < 0 || axis >= ranges.length) continue;
                // Use joystick ranges on composite mouse/gamepad devices.
                InputDevice.MotionRange joystick = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
                if (joystick != null) range = joystick;
                ranges[axis] = new GamepadAxes.Range(range.getMin(), range.getMax(), range.getFlat());
                summary.append(axis).append(':').append(range.getMin()).append("..").append(range.getMax())
                        .append(" flat=").append(range.getFlat()).append(';');
            }
            description = summary.toString();
            selection = new GamepadAxes(this, profile.rightStickAxes);
        }
        public GamepadAxes.Range range(int axis) {
            return axis >= 0 && axis < ranges.length ? ranges[axis] : null;
        }
        public float value(int axis) { return event == null || axis < 0 ? 0 : event.getAxisValue(axis); }
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
                if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
                if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                        && device.getMotionRanges() != null && !device.getMotionRanges().isEmpty()) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }
}
