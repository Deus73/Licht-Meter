package com.growshopsluis.lightmeter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LightSampleWindowTest {
    @Test
    public void becomesStableAfterConsistentTwoSecondWindow() {
        LightSampleWindow window = new LightSampleWindow();
        LightSampleWindow.Snapshot snapshot = null;
        for (int i = 0; i < 12; i++) snapshot = window.add(i * 200L, 1_000 + (i % 3 - 1) * 10);
        assertTrue(snapshot.stable);
        assertEquals(1_000, snapshot.average, 2);
        assertEquals(990, snapshot.minimum, 0);
        assertEquals(1_010, snapshot.maximum, 0);
    }

    @Test
    public void rejectsUnstableAndOldSamples() {
        LightSampleWindow window = new LightSampleWindow();
        LightSampleWindow.Snapshot snapshot = null;
        for (int i = 0; i < 12; i++) snapshot = window.add(i * 200L, i % 2 == 0 ? 500 : 1_500);
        assertFalse(snapshot.stable);
        snapshot = window.add(6_000, 2_000);
        assertEquals(1, snapshot.sampleCount);
        assertEquals(2_000, snapshot.average, 0);
    }
}
