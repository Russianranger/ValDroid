package com.valdroid.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * Physical→logical gamepad button mapping (ported from Zomdroid's GamepadManager idea, simplified).
 *
 * This is the calibration layer: it maps each physical Android keycode to a named controller
 * input, so swapped buttons can be corrected. {@link GamepadProfile} separately selects the
 * output for that input (gamepad, keyboard or mouse); calibration never injects an action.
 *
 * Default is identity (logical A = KEYCODE_BUTTON_A, …). A custom mapping is stored as a CSV of
 * Android keycodes (index = logical button) in SharedPreferences.
 */
public final class GamepadMapping {

    // Logical button indices (order matches the mapper wizard; GUIDE kept for index parity, unused).
    public static final int L_A = 0, L_B = 1, L_X = 2, L_Y = 3, L_LB = 4, L_RB = 5,
            L_SELECT = 6, L_START = 7, L_GUIDE = 8, L_L3 = 9, L_R3 = 10;
    public static final int COUNT = 11;

    /** Steps shown in the wizard, in order (GUIDE skipped — no useful action bound to it). */
    public static final int[] WIZARD_ORDER = { L_A, L_B, L_X, L_Y, L_LB, L_RB, L_SELECT, L_START, L_L3, L_R3 };

    private static final int[] DEFAULT = {
            KeyEvent.KEYCODE_BUTTON_A,      // A
            KeyEvent.KEYCODE_BUTTON_B,      // B
            KeyEvent.KEYCODE_BUTTON_X,      // X
            KeyEvent.KEYCODE_BUTTON_Y,      // Y
            KeyEvent.KEYCODE_BUTTON_L1,     // LB
            KeyEvent.KEYCODE_BUTTON_R1,     // RB
            KeyEvent.KEYCODE_BUTTON_SELECT, // SELECT
            KeyEvent.KEYCODE_BUTTON_START,  // START
            KeyEvent.KEYCODE_BUTTON_MODE,   // GUIDE
            KeyEvent.KEYCODE_BUTTON_THUMBL, // L3
            KeyEvent.KEYCODE_BUTTON_THUMBR  // R3
    };

    private static final String PREFS = "rimdroid_gamepad";
    private static final String KEY_MAPPING = "button_mapping";

    private static int[] current = DEFAULT.clone();

    private GamepadMapping() {}

    /** Load the saved mapping into the in-memory state. Call once before using the gamepad. */
    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String csv = p.getString(KEY_MAPPING, null);
        current = parseOrDefault(csv);
    }

    static int[] parseOrDefault(String csv) {
        if (csv == null || csv.isEmpty()) return DEFAULT.clone();
        String[] parts = csv.split(",");
        if (parts.length != COUNT) return DEFAULT.clone();
        int[] m = new int[COUNT];
        try {
            for (int i = 0; i < COUNT; i++) {
                m[i] = Integer.parseInt(parts[i].trim());
                if (!isCalibrationKey(m[i])) return DEFAULT.clone();
                for (int j = 0; j < i; j++) if (m[j] == m[i]) return DEFAULT.clone();
            }
        } catch (NumberFormatException e) {
            return DEFAULT.clone();
        }
        return m;
    }

    /** Current mapping (logical index → physical Android keycode). Never null, length {@link #COUNT}. */
    public static int[] get() {
        return current;
    }

    public static int[] getDefault() {
        return DEFAULT.clone();
    }

    /** Hats/triggers also arrive as axes; calibrating their key aliases would fire two rows. */
    public static boolean isCalibrationKey(int code) {
        return code > KeyEvent.KEYCODE_UNKNOWN && code != KeyEvent.KEYCODE_BUTTON_L2
                && code != KeyEvent.KEYCODE_BUTTON_R2 && code != KeyEvent.KEYCODE_DPAD_UP
                && code != KeyEvent.KEYCODE_DPAD_RIGHT && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_CENTER;
    }

    /** Persist + apply a custom mapping (logical index → physical keycode). */
    public static void save(Context ctx, int[] mapping) {
        if (mapping == null || mapping.length != COUNT) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(mapping[i]);
        }
        String csv = sb.toString();
        int[] validated = parseOrDefault(csv);
        if (!java.util.Arrays.equals(validated, mapping)) return;
        current = validated;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAPPING, csv).apply();
    }

    /** Reset to the identity default. */
    public static void reset(Context ctx) {
        current = DEFAULT.clone();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MAPPING).apply();
    }

    /** @return logical button index for a physical keycode, or -1 if it isn't a mapped button. */
    public static int toLogical(int keyCode) {
        for (int i = 0; i < COUNT; i++) if (current[i] == keyCode) return i;
        return -1;
    }
}
