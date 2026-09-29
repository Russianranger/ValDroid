package com.valdroid.input;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class GamepadOutputArbiterTest {
    private static final class Recorder implements GamepadOutputArbiter.Sink {
        final List<String> events = new ArrayList<>();
        public void button(int code, boolean down) { events.add("b" + code + ":" + down); }
        public void axis(int code, int value) { events.add("a" + code + ":" + value); }
        public void sync() { events.add("sync"); }
    }

    @Test public void touchCannotCancelOrAddPhysicalOutputs() {
        Recorder r = new Recorder();
        GamepadOutputArbiter a = new GamepadOutputArbiter(r);
        a.setPhysicalConnected(true);
        a.button(true, 304, true);
        a.axis(true, 3, 12000);
        a.sync(true);
        r.events.clear();
        a.button(false, 304, false);
        a.button(false, 305, true);
        a.axis(false, 3, 0);
        a.axis(false, 4, -20000);
        a.sync(false);
        assertTrue(r.events.isEmpty());
        a.button(true, 304, false);
        a.axis(true, 3, 0);
        a.sync(true);
        assertEquals(Arrays.asList("b304:false", "a3:0", "sync"), r.events);
    }

    @Test public void connectionNeutralizesTouchBeforePhysicalInput() {
        Recorder r = new Recorder();
        GamepadOutputArbiter a = new GamepadOutputArbiter(r);
        a.button(false, 304, true);
        a.axis(false, 0, -5000);
        a.sync(false);
        r.events.clear();
        a.setPhysicalConnected(true);
        a.button(true, 304, true);
        a.sync(true);
        assertEquals(Arrays.asList("b304:false", "a0:0", "sync", "b304:true", "sync"), r.events);
    }

    @Test public void disconnectClearsPhysicalHoldsAndRestoresTouch() {
        Recorder r = new Recorder();
        GamepadOutputArbiter a = new GamepadOutputArbiter(r);
        a.setPhysicalConnected(true);
        a.button(true, 305, true);
        a.axis(true, 2, 128);
        a.sync(true);
        r.events.clear();
        a.setPhysicalConnected(false);
        a.button(true, 305, true); // late event from the old owner
        a.button(false, 305, true);
        a.sync(false);
        assertEquals(Arrays.asList("b305:false", "a2:0", "sync", "b305:true", "sync"), r.events);
    }

    @Test public void repeatedReportsAndUnchangedConnectionDoNotRepeatButtons() {
        Recorder r = new Recorder();
        GamepadOutputArbiter a = new GamepadOutputArbiter(r);
        a.setPhysicalConnected(true);
        for (int i = 0; i < 3; i++) {
            a.setPhysicalConnected(true);
            a.button(true, 304, true);
            a.axis(true, 0, 10000);
            a.sync(true);
        }
        a.button(true, 304, false);
        a.button(true, 304, false);
        a.sync(true);
        assertEquals(Arrays.asList("b304:true", "a0:10000", "sync", "b304:false", "sync"), r.events);
    }

    @Test public void wrongSourceCannotFlushPendingBatchButHandoverDoes() {
        Recorder r = new Recorder();
        GamepadOutputArbiter a = new GamepadOutputArbiter(r);
        a.button(false, 304, true);
        a.button(false, 304, false);
        a.sync(true);
        assertEquals(Arrays.asList("b304:true", "b304:false"), r.events);
        a.setPhysicalConnected(true);
        assertEquals(Arrays.asList("b304:true", "b304:false", "sync"), r.events);
    }
}
