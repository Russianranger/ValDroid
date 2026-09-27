package com.valdroid.input;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class GamepadMappingTest {
    @Test public void preservesExistingButtonSwap() {
        int[] swapped = GamepadMapping.getDefault();
        int a = swapped[GamepadMapping.L_A];
        swapped[GamepadMapping.L_A] = swapped[GamepadMapping.L_B];
        swapped[GamepadMapping.L_B] = a;
        assertArrayEquals(swapped, GamepadMapping.parseOrDefault(csv(swapped)));
    }

    @Test public void rejectsLegacyTriggerAndHatAliasesThatWouldFireTwoInputs() {
        for (int code : new int[]{KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT}) {
            int[] invalid = GamepadMapping.getDefault();
            invalid[GamepadMapping.L_A] = code;
            assertArrayEquals(GamepadMapping.getDefault(), GamepadMapping.parseOrDefault(csv(invalid)));
        }
    }

    @Test public void rejectsDuplicatesAndMalformedCalibration() {
        int[] invalid = GamepadMapping.getDefault();
        invalid[GamepadMapping.L_A] = invalid[GamepadMapping.L_GUIDE];
        assertArrayEquals(GamepadMapping.getDefault(), GamepadMapping.parseOrDefault(csv(invalid)));
        assertArrayEquals(GamepadMapping.getDefault(), GamepadMapping.parseOrDefault("not,a,mapping"));
    }

    @Test public void supportsControllersWithAndroidBackAsAButton() {
        int[] mapping = GamepadMapping.getDefault();
        mapping[GamepadMapping.L_B] = KeyEvent.KEYCODE_BACK;
        assertArrayEquals(mapping, GamepadMapping.parseOrDefault(csv(mapping)));
    }

    private static String csv(int[] mapping) {
        StringBuilder result = new StringBuilder();
        for (int code : mapping) {
            if (result.length() > 0) result.append(',');
            result.append(code);
        }
        return result.toString();
    }
}
