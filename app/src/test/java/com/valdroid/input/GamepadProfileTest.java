package com.valdroid.input;

import org.junit.Test;

import static org.junit.Assert.*;

public class GamepadProfileTest {
    @Test public void existingInstallKeepsGamepadOutputsWithoutNewSettings() {
        GamepadProfile profile = GamepadProfile.parse(null);
        assertEquals(Binding.GAMEPAD_BUTTON_A, profile.get(GamepadProfile.Input.A));
        assertEquals(Binding.GAMEPAD_LTRIGGER, profile.get(GamepadProfile.Input.LT));
        assertEquals(GamepadProfile.StickMode.GAMEPAD_LEFT, profile.leftStick);
        assertEquals(GamepadProfile.StickMode.GAMEPAD_RIGHT, profile.rightStick);
    }

    @Test public void keyboardPresetHasNoGamepadOutput() {
        GamepadProfile profile = GamepadProfile.mouseKeyboardDefaults();
        for (GamepadProfile.Input input : GamepadProfile.Input.values()) {
            assertFalse(input.name(), profile.get(input).isGamepad());
        }
        assertEquals(GamepadProfile.StickMode.DIRECTIONS, profile.leftStick);
        assertEquals(GamepadProfile.StickMode.MOUSE, profile.rightStick);
    }

    @Test public void customMixedOutputsAndDisabledInputsSurviveSaving() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(GamepadProfile.Input.A, Binding.KEY_F10);
        profile.set(GamepadProfile.Input.LT, Binding.GAMEPAD_RTRIGGER);
        profile.set(GamepadProfile.Input.RT, Binding.NONE);
        profile.set(GamepadProfile.Input.LS_UP, Binding.MOUSE_MOVE_LEFT);
        profile.leftStick = GamepadProfile.StickMode.DIRECTIONS;
        profile.rightStick = GamepadProfile.StickMode.DISABLED;
        GamepadProfile restored = GamepadProfile.parse(profile.serialize());
        assertEquals(profile.serialize(), restored.serialize());
    }

    @Test public void editingDraftDoesNotChangeSourceProfile() {
        GamepadProfile original = GamepadProfile.gamepadDefaults();
        GamepadProfile draft = original.copy();
        draft.set(GamepadProfile.Input.A, Binding.NONE);
        draft.leftStick = GamepadProfile.StickMode.MOUSE;
        assertEquals(Binding.GAMEPAD_BUTTON_A, original.get(GamepadProfile.Input.A));
        assertEquals(GamepadProfile.StickMode.GAMEPAD_LEFT, original.leftStick);
    }

    @Test public void damagedRecognizedProfileDoesNotRestorePassthroughOnMissingOutputs() {
        GamepadProfile profile = GamepadProfile.parse("version=1\nleft=missing\nA=KEY_W\nB=removed_action\n");
        assertEquals(Binding.KEY_W, profile.get(GamepadProfile.Input.A));
        assertEquals(Binding.NONE, profile.get(GamepadProfile.Input.B));
        assertEquals(Binding.NONE, profile.get(GamepadProfile.Input.RT));
        assertEquals(GamepadProfile.StickMode.DISABLED, profile.leftStick);
        assertEquals(GamepadProfile.StickMode.DISABLED, profile.rightStick);
    }

    @Test public void analogStickCannotBeSavedAsDigitalOutput() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(GamepadProfile.Input.A, Binding.LEFT_JOYSTICK);
        assertEquals(Binding.NONE, profile.get(GamepadProfile.Input.A));
    }
}
