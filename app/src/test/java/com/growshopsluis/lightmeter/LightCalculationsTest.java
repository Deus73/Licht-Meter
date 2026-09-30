package com.growshopsluis.lightmeter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LightCalculationsTest {
    @Test
    public void estimatesLuxFromExposureValue() {
        // f/2, 1/100 s, ISO 100 gives EV 8.64 and approximately 1000 lux at target luma.
        double lux = LightCalculations.estimateLux(10_000_000, 100, 2f, 0.18, 1.0);
        assertEquals(1000, lux, 0.1);
    }

    @Test
    public void convertsLuxToPpfdAndLumens() {
        assertEquals(150, LightCalculations.estimatePpfd(10_000, 0.015), 0.001);
        assertEquals(12_000, LightCalculations.estimateLumens(10_000, 1.2), 0.001);
        assertEquals(92.903, LightCalculations.luxToFootCandles(1_000), 0.001);
    }

    @Test
    public void rejectsInvalidExposure() {
        assertEquals(0, LightCalculations.estimateLux(0, 100, 2f, 0.18, 1), 0);
    }
}
