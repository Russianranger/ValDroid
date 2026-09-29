package com.valdroid.input;

import android.content.Context;

/** App-wide controller outputs, stored separately from existing physical button calibration. */
public final class GamepadProfileStore {
    private static final String PREFS = "rimdroid_gamepad";
    private static final String KEY_PROFILE = "output_profile_v1";

    private GamepadProfileStore() {}

    public static GamepadProfile load(Context context) {
        return GamepadProfile.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROFILE, null));
    }

    public static void save(Context context, GamepadProfile profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, profile.serialize()).apply();
    }
}
