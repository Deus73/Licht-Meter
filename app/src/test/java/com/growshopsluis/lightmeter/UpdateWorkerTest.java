package com.growshopsluis.lightmeter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdateWorkerTest {
    @Test
    public void comparesSemanticVersions() {
        assertTrue(UpdateWorker.compareVersions("1.3.1", "1.3.0") > 0);
        assertTrue(UpdateWorker.compareVersions("2.0.0", "1.99.99") > 0);
        assertTrue(UpdateWorker.compareVersions("1.2.9", "1.3.0") < 0);
        assertEquals(0, UpdateWorker.compareVersions("1.3", "1.3.0"));
        assertEquals(0, UpdateWorker.compareVersions("1.3.0-beta", "1.3.0"));
    }
}
