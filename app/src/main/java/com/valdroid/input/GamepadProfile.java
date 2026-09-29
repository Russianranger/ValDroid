package com.valdroid.input;

import java.util.EnumMap;

/** Editable outputs for a physical controller, independent of Android and input injection. */
public final class GamepadProfile {
    public enum Input {
        A("A"), B("B"), X("X"), Y("Y"), LB("LB"), RB("RB"),
        SELECT("Select / Back"), START("Start"), GUIDE("Guide"),
        L3("L3 / left stick click"), R3("R3 / right stick click"),
        LT("LT"), RT("RT"), DPAD_UP("D-pad up"), DPAD_RIGHT("D-pad right"),
        DPAD_DOWN("D-pad down"), DPAD_LEFT("D-pad left"),
        LS_UP("Left stick up"), LS_RIGHT("Left stick right"),
        LS_DOWN("Left stick down"), LS_LEFT("Left stick left"),
        RS_UP("Right stick up"), RS_RIGHT("Right stick right"),
        RS_DOWN("Right stick down"), RS_LEFT("Right stick left");

        public final String label;
        Input(String label) { this.label = label; }
    }

    public enum StickMode {
        GAMEPAD_LEFT("Gamepad left stick"), GAMEPAD_RIGHT("Gamepad right stick"),
        MOUSE("Mouse cursor / look"), DIRECTIONS("Custom direction bindings"),
        DISABLED("Disabled");

        public final String label;
        StickMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum RightStickAxes {
        AUTO("Automatic"), Z_RZ("Z / RZ"), RX_RY("RX / RY");
        public final String label;
        RightStickAxes(String label) { this.label = label; }
    }

    private final EnumMap<Input, Binding> bindings = new EnumMap<>(Input.class);
    public StickMode leftStick = StickMode.GAMEPAD_LEFT;
    public StickMode rightStick = StickMode.GAMEPAD_RIGHT;
    public RightStickAxes rightStickAxes = RightStickAxes.AUTO;

    private GamepadProfile() {
        for (Input input : Input.values()) bindings.put(input, Binding.NONE);
    }

    public Binding get(Input input) { return bindings.get(input); }

    public void set(Input input, Binding binding) {
        if (input == null) throw new IllegalArgumentException("Missing controller input");
        // Analog stick destinations belong to StickMode, never to a digital binding.
        bindings.put(input, binding == null || binding.kind == Binding.Kind.GP_STICK
                ? Binding.NONE : binding);
    }

    public GamepadProfile copy() {
        GamepadProfile copy = new GamepadProfile();
        copy.bindings.putAll(bindings);
        copy.leftStick = leftStick;
        copy.rightStick = rightStick;
        copy.rightStickAxes = rightStickAxes;
        return copy;
    }

    /** Whether this profile needs a visible pointer while the game has not captured the mouse. */
    public boolean usesMouse() {
        if (leftStick == StickMode.MOUSE || rightStick == StickMode.MOUSE) return true;
        for (Input input : Input.values()) {
            if (input.name().startsWith("LS_") && leftStick != StickMode.DIRECTIONS) continue;
            if (input.name().startsWith("RS_") && rightStick != StickMode.DIRECTIONS) continue;
            Binding.Kind kind = get(input).kind;
            if (kind == Binding.Kind.MOUSE || kind == Binding.Kind.SCROLL
                    || kind == Binding.Kind.MOUSE_MOVE) return true;
        }
        return false;
    }

    /** Matches the original passthrough behavior, including any separately saved calibration. */
    public static GamepadProfile gamepadDefaults() {
        GamepadProfile p = new GamepadProfile();
        p.set(Input.A, Binding.GAMEPAD_BUTTON_A);
        p.set(Input.B, Binding.GAMEPAD_BUTTON_B);
        p.set(Input.X, Binding.GAMEPAD_BUTTON_X);
        p.set(Input.Y, Binding.GAMEPAD_BUTTON_Y);
        p.set(Input.LB, Binding.GAMEPAD_BUTTON_LB);
        p.set(Input.RB, Binding.GAMEPAD_BUTTON_RB);
        p.set(Input.SELECT, Binding.GAMEPAD_BUTTON_BACK);
        p.set(Input.START, Binding.GAMEPAD_BUTTON_START);
        p.set(Input.GUIDE, Binding.GAMEPAD_BUTTON_GUIDE);
        p.set(Input.L3, Binding.GAMEPAD_BUTTON_LSTICK);
        p.set(Input.R3, Binding.GAMEPAD_BUTTON_RSTICK);
        p.set(Input.LT, Binding.GAMEPAD_LTRIGGER);
        p.set(Input.RT, Binding.GAMEPAD_RTRIGGER);
        p.set(Input.DPAD_UP, Binding.GAMEPAD_DPAD_UP);
        p.set(Input.DPAD_RIGHT, Binding.GAMEPAD_DPAD_RIGHT);
        p.set(Input.DPAD_DOWN, Binding.GAMEPAD_DPAD_DOWN);
        p.set(Input.DPAD_LEFT, Binding.GAMEPAD_DPAD_LEFT);
        return p;
    }

    /** A fully editable WASD/mouse starting layout; no virtual gamepad output. */
    public static GamepadProfile mouseKeyboardDefaults() {
        GamepadProfile p = new GamepadProfile();
        p.leftStick = StickMode.DIRECTIONS;
        p.rightStick = StickMode.MOUSE;
        p.set(Input.A, Binding.KEY_SPACE);
        p.set(Input.B, Binding.KEY_ESCAPE);
        p.set(Input.X, Binding.KEY_E);
        p.set(Input.Y, Binding.KEY_R);
        p.set(Input.LB, Binding.KEY_LCTRL);
        p.set(Input.RB, Binding.KEY_LSHIFT);
        p.set(Input.SELECT, Binding.KEY_TAB);
        p.set(Input.START, Binding.KEY_ESCAPE);
        p.set(Input.L3, Binding.KEY_LSHIFT);
        p.set(Input.R3, Binding.MOUSE_MIDDLE);
        p.set(Input.LT, Binding.MOUSE_RIGHT);
        p.set(Input.RT, Binding.MOUSE_LEFT);
        p.set(Input.DPAD_UP, Binding.KEY_1);
        p.set(Input.DPAD_RIGHT, Binding.KEY_2);
        p.set(Input.DPAD_DOWN, Binding.KEY_3);
        p.set(Input.DPAD_LEFT, Binding.KEY_4);
        p.set(Input.LS_UP, Binding.KEY_W);
        p.set(Input.LS_RIGHT, Binding.KEY_D);
        p.set(Input.LS_DOWN, Binding.KEY_S);
        p.set(Input.LS_LEFT, Binding.KEY_A);
        return p;
    }

    /** Stable names rather than enum ordinals allow later additions without shifting mappings. */
    public String serialize() {
        StringBuilder result = new StringBuilder("version=1\n");
        result.append("left=").append(leftStick.name()).append('\n');
        result.append("right=").append(rightStick.name()).append('\n');
        result.append("rightAxes=").append(rightStickAxes.name()).append('\n');
        for (Input input : Input.values()) {
            result.append(input.name()).append('=').append(get(input).name()).append('\n');
        }
        return result.toString();
    }

    public static GamepadProfile parse(String saved) {
        if (saved == null || !saved.startsWith("version=1\n")) return gamepadDefaults();
        // Unknown/missing actions in a recognized profile are disabled, not silently passed through.
        GamepadProfile result = new GamepadProfile();
        result.leftStick = result.rightStick = StickMode.DISABLED;
        for (String line : saved.split("\n")) {
            int equals = line.indexOf('=');
            if (equals < 1) continue;
            String name = line.substring(0, equals), value = line.substring(equals + 1);
            try {
                if ("left".equals(name)) result.leftStick = StickMode.valueOf(value);
                else if ("right".equals(name)) result.rightStick = StickMode.valueOf(value);
                else if ("rightAxes".equals(name)) result.rightStickAxes = RightStickAxes.valueOf(value);
                else if (!"version".equals(name)) result.set(Input.valueOf(name), Binding.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // A renamed/removed output must not acquire a different action after an update.
            }
        }
        return result;
    }
}
