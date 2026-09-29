package com.valdroid.input;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static android.view.MotionEvent.*;
import static com.valdroid.input.GamepadProfile.RightStickAxes.*;
import static org.junit.Assert.*;

public class GamepadAxesTest {
    private static final class Device implements GamepadAxes.Source {
        final Map<Integer, GamepadAxes.Range> ranges = new HashMap<>();
        final Map<Integer, Float> values = new HashMap<>();
        Device axis(int axis, float min, float max, float value) {
            ranges.put(axis, new GamepadAxes.Range(min, max, 0));
            values.put(axis, value);
            return this;
        }
        public GamepadAxes.Range range(int axis) { return ranges.get(axis); }
        public float value(int axis) { return values.getOrDefault(axis, 0f); }
    }

    @Test public void standardStickNeverAlsoBecomesATrigger() {
        Device d = new Device().axis(AXIS_Z, -1, 1, 1).axis(AXIS_RZ, -1, 1, -1);
        GamepadAxes a = new GamepadAxes(d, AUTO);
        assertEquals(AXIS_Z, a.rightX);
        assertEquals(AXIS_RZ, a.rightY);
        assertEquals(GamepadAxes.NONE, a.leftTrigger);
        assertEquals(GamepadAxes.NONE, a.rightTrigger);
        assertEquals(-1, a.stick(a.rightY), 0);
    }

    @Test public void unsignedZTriggersDoNotStealRxRyStick() {
        Device d = new Device().axis(AXIS_Z, 0, 1, 0.4f).axis(AXIS_RZ, 0, 1, 0.7f)
                .axis(AXIS_RX, -1, 1, -0.8f).axis(AXIS_RY, -1, 1, 0.5f);
        GamepadAxes a = new GamepadAxes(d, AUTO);
        assertEquals(AXIS_RX, a.rightX);
        assertEquals(AXIS_RY, a.rightY);
        assertEquals(0.4f, a.trigger(a.leftTrigger), 0.001f);
        assertEquals(-0.8f, a.stick(a.rightX), 0.001f);
    }

    @Test public void incompleteOrNonCenteredPairFallsBackAsAWhole() {
        Device d = new Device().axis(AXIS_Z, -1, 1, 1)
                .axis(AXIS_RX, -1, 1, 0.5f).axis(AXIS_RY, -1, 1, -0.5f);
        assertEquals(AXIS_RX, new GamepadAxes(d, AUTO).rightX);
        d.axis(AXIS_RZ, 0, 1, 1);
        assertEquals(AXIS_RY, new GamepadAxes(d, AUTO).rightY);
        d.ranges.remove(AXIS_RY);
        assertEquals(GamepadAxes.NONE, new GamepadAxes(d, AUTO).rightX);
    }

    @Test public void explicitPairDoesNotReadAliasesOrFallBackSilently() {
        Device d = new Device().axis(AXIS_Z, -1, 1, 1).axis(AXIS_RZ, -1, 1, 1)
                .axis(AXIS_RX, -1, 1, -1).axis(AXIS_RY, -1, 1, -1);
        GamepadAxes a = new GamepadAxes(d, RX_RY);
        assertEquals(-1, a.stick(a.rightX), 0);
        assertEquals(GamepadAxes.NONE, a.leftTrigger);
        assertEquals(AXIS_Z, new GamepadAxes(d, Z_RZ).rightX);
        d.ranges.remove(AXIS_RY);
        assertEquals(GamepadAxes.NONE, new GamepadAxes(d, RX_RY).rightX);
    }

    @Test public void dedicatedTriggersWinOverBrakeGasAndGenericAxes() {
        Device d = new Device().axis(AXIS_LTRIGGER, 0, 1, 0.25f)
                .axis(AXIS_RTRIGGER, 0, 1, 0.5f).axis(AXIS_BRAKE, 0, 1, 1)
                .axis(AXIS_GAS, 0, 1, 1).axis(AXIS_GENERIC_1, 0, 1, 1);
        GamepadAxes a = new GamepadAxes(d, AUTO);
        assertEquals(0.25f, a.trigger(a.leftTrigger), 0);
        assertEquals(0.5f, a.trigger(a.rightTrigger), 0);
        d.ranges.remove(AXIS_LTRIGGER);
        assertEquals(AXIS_BRAKE, new GamepadAxes(d, AUTO).leftTrigger);
        d.ranges.remove(AXIS_BRAKE);
        assertEquals(GamepadAxes.NONE, new GamepadAxes(d, AUTO).leftTrigger);
    }

    @Test public void signedTriggerRangeNormalizesRestAndTravel() {
        Device d = new Device().axis(AXIS_LTRIGGER, -1, 1, -1);
        GamepadAxes a = new GamepadAxes(d, AUTO);
        assertEquals(0, a.trigger(a.leftTrigger), 0);
        d.values.put(AXIS_LTRIGGER, 0f);
        assertEquals(0.5f, a.trigger(a.leftTrigger), 0);
        d.values.put(AXIS_LTRIGGER, 2f);
        assertEquals(1, a.trigger(a.leftTrigger), 0);
    }

    @Test public void stickNormalizationDeadzoneAndInvalidValuesAreSafe() {
        Device d = new Device().axis(AXIS_X, -32768, 32767, 1000);
        GamepadAxes a = new GamepadAxes(d, AUTO);
        assertEquals(0, a.stick(AXIS_X), 0);
        d.values.put(AXIS_X, -32768f);
        assertEquals(-1, a.stick(AXIS_X), 0);
        d.ranges.put(AXIS_X, new GamepadAxes.Range(-1, 1, 0.25f));
        d.values.put(AXIS_X, 0.2f);
        assertEquals(0, a.stick(AXIS_X), 0);
        d.values.put(AXIS_X, Float.NaN);
        assertEquals(0, a.stick(AXIS_X), 0);
        d.axis(AXIS_LTRIGGER, 0, 0, 1);
        assertEquals(GamepadAxes.NONE, new GamepadAxes(d, AUTO).leftTrigger);
        d.axis(AXIS_RTRIGGER, 0, 1, Float.POSITIVE_INFINITY);
        assertEquals(0, new GamepadAxes(d, AUTO).trigger(AXIS_RTRIGGER), 0);
    }
}
