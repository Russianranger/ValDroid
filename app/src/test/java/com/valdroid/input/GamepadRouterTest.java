package com.valdroid.input;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.valdroid.input.GamepadProfile.Input;
import static com.valdroid.input.GamepadProfile.StickMode;
import static org.junit.Assert.*;

/** Regressions for duplicated input, remap leakage, analog routing and stuck controls. */
public class GamepadRouterTest {
    private static final class RecordingSink implements GamepadRouter.Sink {
        final List<String> injected = new ArrayList<>();
        final List<String> buttons = new ArrayList<>();
        final List<String> axisEvents = new ArrayList<>();
        final Map<Integer, Integer> axes = new HashMap<>();
        float dx, dy;
        int syncs;
        @Override public void inject(Binding binding, boolean down) {
            injected.add(binding.name() + ":" + down);
        }
        @Override public void moveCursor(float x, float y) { dx += x; dy += y; }
        @Override public void gamepadButton(int code, boolean down) {
            buttons.add(code + ":" + down);
        }
        @Override public void gamepadAxis(int code, int value) {
            axes.put(code, value); axisEvents.add(code + ":" + value);
        }
        @Override public void syncGamepad() { syncs++; }
        int axis(int code) { return axes.containsKey(code) ? axes.get(code) : 0; }
        int count(Binding binding, boolean down) {
            int result = 0;
            for (String event : injected) if (event.equals(binding.name() + ":" + down)) result++;
            return result;
        }
        void assertNoGamepadEvents() {
            assertTrue(buttons.isEmpty()); assertTrue(axisEvents.isEmpty()); assertEquals(0, syncs);
        }
    }

    @Test public void keyboardRemapReplacesOriginalGamepadAndPreservesFastTap() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(Input.A, Binding.KEY_ENTER);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.key(1, Input.A, true);
        router.key(1, Input.A, false); // Both before the next display frame.
        assertEquals(1, sink.count(Binding.KEY_ENTER, true));
        assertEquals(1, sink.count(Binding.KEY_ENTER, false));
        sink.assertNoGamepadEvents();
    }

    @Test public void sharedOutputHeldUntilLastLogicalSourceReleases() {
        GamepadProfile profile = GamepadProfile.mouseKeyboardDefaults();
        profile.set(Input.A, Binding.KEY_SPACE);
        profile.set(Input.B, Binding.KEY_SPACE);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.key(1, Input.A, true);
        router.key(1, Input.B, true);
        router.key(1, Input.A, false);
        assertEquals(1, sink.count(Binding.KEY_SPACE, true));
        assertEquals(0, sink.count(Binding.KEY_SPACE, false));
        router.key(1, Input.B, false);
        assertEquals(1, sink.count(Binding.KEY_SPACE, false));
    }

    @Test public void duplicatePhysicalKeysKeepCalibratedLogicalInputHeld() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.mouseKeyboardDefaults(), sink);
        router.key(1, 4, Input.B, true);
        router.key(1, 97, Input.B, true);
        router.key(1, 4, Input.B, false);
        assertEquals(0, sink.count(Binding.KEY_ESCAPE, false));
        router.key(1, 97, Input.B, false);
        assertEquals(1, sink.count(Binding.KEY_ESCAPE, true));
        assertEquals(1, sink.count(Binding.KEY_ESCAPE, false));
    }

    @Test public void keyAndAxisTriggerReportsPreserveAnalogTravel() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.gamepadDefaults(), sink);
        router.key(1, Input.LT, true);
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(255, sink.axis(VirtualGamepad.ABS_Z));
        router.motion(1, 0, 0, 0, 0, 0.5f, 0, 0, 0);
        assertEquals(128, sink.axis(VirtualGamepad.ABS_Z));
        router.key(1, Input.LT, false);
        assertEquals(128, sink.axis(VirtualGamepad.ABS_Z));
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_Z));
    }

    @Test public void analogTriggerCapabilityPreventsDigitalAliasSaturatingTravel() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.gamepadDefaults(), sink);
        router.triggerCapabilities(1, true, true);
        router.key(1, Input.LT, true);
        assertTrue(sink.axisEvents.isEmpty());
        router.motion(1, 0, 0, 0, 0, 0.4f, 0, 0, 0, true, true);
        router.key(1, Input.LT, true);
        assertEquals(102, sink.axis(VirtualGamepad.ABS_Z));
        router.motion(1, 0, 0, 0, 0, 0.7f, 0, 0, 0, true, true);
        assertEquals(179, sink.axis(VirtualGamepad.ABS_Z));
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0, true, true);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_Z));
        router.key(1, Input.LT, false);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_Z));
    }

    @Test public void analogTriggerCanMoveToOtherTriggerWithoutLeakingOriginal() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(Input.LT, Binding.GAMEPAD_RTRIGGER);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.motion(1, 0, 0, 0, 0, 0.1f, 0, 0, 0);
        assertEquals(26, sink.axis(VirtualGamepad.ABS_RZ));
        assertFalse(sink.axes.containsKey(VirtualGamepad.ABS_Z));
        assertTrue(sink.injected.isEmpty());
    }

    @Test public void triggerToKeyboardUsesThresholdAndKeyReportStillHolds() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.mouseKeyboardDefaults(), sink);
        router.motion(1, 0, 0, 0, 0, 0, 0.1f, 0, 0);
        assertEquals(0, sink.count(Binding.MOUSE_LEFT, true));
        router.motion(1, 0, 0, 0, 0, 0, 0.7f, 0, 0);
        router.key(1, Input.RT, true);
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, sink.count(Binding.MOUSE_LEFT, true));
        assertEquals(0, sink.count(Binding.MOUSE_LEFT, false));
        router.key(1, Input.RT, false);
        assertEquals(1, sink.count(Binding.MOUSE_LEFT, false));
        sink.assertNoGamepadEvents();
    }

    @Test public void dpadKeyAndHatDoNotClearEachOther() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.gamepadDefaults(), sink);
        router.key(1, Input.DPAD_UP, true);
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(-1, sink.axis(VirtualGamepad.ABS_HAT0Y));
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, -1);
        router.key(1, Input.DPAD_UP, false);
        assertEquals(-1, sink.axis(VirtualGamepad.ABS_HAT0Y));
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_HAT0Y));
        assertEquals(2, sink.axisEvents.size());
    }

    @Test public void opposingDpadKeysNeutralizeThenRemainingDirectionRestores() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.gamepadDefaults(), sink);
        router.key(1, Input.DPAD_LEFT, true);
        router.key(1, Input.DPAD_RIGHT, true);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_HAT0X));
        router.key(1, Input.DPAD_LEFT, false);
        assertEquals(1, sink.axis(VirtualGamepad.ABS_HAT0X));
        router.key(1, Input.DPAD_RIGHT, false);
        assertEquals(0, sink.axis(VirtualGamepad.ABS_HAT0X));
    }

    @Test public void digitalAndAnalogRemapsShareTriggerByStrongestValue() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(Input.A, Binding.GAMEPAD_LTRIGGER);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.motion(1, 0, 0, 0, 0, 0.4f, 0, 0, 0);
        router.key(1, Input.A, true);
        assertEquals(255, sink.axis(VirtualGamepad.ABS_Z));
        router.key(1, Input.A, false);
        assertEquals(102, sink.axis(VirtualGamepad.ABS_Z));
        assertTrue(sink.buttons.isEmpty());
    }

    @Test public void sticksCanSwapGamepadAxesOrBeDisabled() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.leftStick = StickMode.GAMEPAD_RIGHT;
        profile.rightStick = StickMode.DISABLED;
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.motion(1, 0.5f, -1, 1, 1, 0, 0, 0, 0);
        assertEquals(16384, sink.axis(VirtualGamepad.ABS_RX));
        assertEquals(-32767, sink.axis(VirtualGamepad.ABS_RY));
        assertFalse(sink.axes.containsKey(VirtualGamepad.ABS_X));
        assertFalse(sink.axes.containsKey(VirtualGamepad.ABS_Y));
    }

    @Test public void mouseKeyboardPresetDoesNotEmitAnyGamepadReports() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.mouseKeyboardDefaults(), sink);
        for (Input input : Input.values()) router.key(1, input, true);
        router.motion(1, -1, 1, 1, -1, 1, 1, -1, 1);
        router.frame(0.1f);
        assertTrue(sink.dx > 0); assertTrue(sink.dy < 0);
        router.releaseAll();
        sink.assertNoGamepadEvents();
    }

    @Test public void directionBindingsCanMoveMouseAndHoldKeysTogether() {
        GamepadProfile profile = GamepadProfile.mouseKeyboardDefaults();
        profile.leftStick = StickMode.DISABLED;
        profile.rightStick = StickMode.DIRECTIONS;
        profile.set(Input.RS_UP, Binding.MOUSE_MOVE_UP);
        profile.set(Input.RS_RIGHT, Binding.KEY_D);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.motion(1, 0, 0, 0.8f, -0.8f, 0, 0, 0, 0);
        router.frame(0.1f);
        assertEquals(0f, sink.dx, 0f); assertTrue(sink.dy < 0);
        assertEquals(1, sink.count(Binding.KEY_D, true));
        router.motion(1, 0, 0, 0, 0, 0, 0, 0, 0);
        float lastY = sink.dy;
        router.frame(0.1f);
        assertEquals(lastY, sink.dy, 0f);
        assertEquals(1, sink.count(Binding.KEY_D, false));
        sink.assertNoGamepadEvents();
    }

    @Test public void heldMouseDirectionRepeatsPerFrameAndStopsOnRelease() {
        GamepadProfile profile = GamepadProfile.mouseKeyboardDefaults();
        profile.set(Input.A, Binding.MOUSE_MOVE_RIGHT);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.key(1, Input.A, true);
        router.frame(0.1f); router.frame(0.1f);
        assertEquals(280f, sink.dx, 0.001f);
        router.key(1, Input.A, false); router.frame(0.1f);
        assertEquals(280f, sink.dx, 0.001f);
        assertTrue(sink.injected.isEmpty());
    }

    @Test public void wheelRepeatsWhileSpecialActionsOnlyFireOnPressEdge() {
        GamepadProfile profile = GamepadProfile.mouseKeyboardDefaults();
        profile.set(Input.A, Binding.SCROLL_UP);
        profile.set(Input.B, Binding.TOGGLE_KEYBOARD);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.key(1, Input.A, true); router.key(1, Input.B, true);
        router.frame(0.05f); router.frame(0.05f); router.frame(0.09f);
        assertEquals(3, sink.count(Binding.SCROLL_UP, true));
        assertEquals(1, sink.count(Binding.TOGGLE_KEYBOARD, true));
        router.releaseAll(); router.frame(0.1f);
        assertEquals(3, sink.count(Binding.SCROLL_UP, true));
    }

    @Test public void disconnectReleasesOnlyThatDeviceAndRetainsSharedOutput() {
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(GamepadProfile.gamepadDefaults(), sink);
        router.key(1, Input.A, true); router.key(2, Input.A, true);
        router.motion(1, 1, 0, 0, 0, 0, 0, 0, 0);
        router.removeDevice(1);
        assertEquals(1, sink.buttons.size());
        assertEquals(0, sink.axis(VirtualGamepad.ABS_X));
        router.removeDevice(2);
        assertEquals(2, sink.buttons.size());
        assertEquals(VirtualGamepad.BTN_A + ":false", sink.buttons.get(1));
    }

    @Test public void stopReleasesAllOwnedOutputsOnceAndNeverWritesUnownedAxes() {
        GamepadProfile profile = GamepadProfile.gamepadDefaults();
        profile.set(Input.B, Binding.KEY_E);
        RecordingSink sink = new RecordingSink();
        GamepadRouter router = new GamepadRouter(profile, sink);
        router.frame(0.1f);
        sink.assertNoGamepadEvents();
        router.key(1, Input.A, true); router.key(1, Input.B, true);
        router.motion(1, 0.3f, 0, 0, 0, 0.2f, 0, 0, -1);
        router.releaseAll();
        assertEquals(0, sink.axis(VirtualGamepad.ABS_X));
        assertEquals(0, sink.axis(VirtualGamepad.ABS_Z));
        assertEquals(0, sink.axis(VirtualGamepad.ABS_HAT0Y));
        assertEquals(1, sink.count(Binding.KEY_E, false));
        assertFalse(sink.axes.containsKey(VirtualGamepad.ABS_RX));
        assertFalse(sink.axes.containsKey(VirtualGamepad.ABS_RZ));
        int syncs = sink.syncs, events = sink.axisEvents.size();
        router.releaseAll(); router.frame(0.1f);
        assertEquals(syncs, sink.syncs); assertEquals(events, sink.axisEvents.size());
    }
}
