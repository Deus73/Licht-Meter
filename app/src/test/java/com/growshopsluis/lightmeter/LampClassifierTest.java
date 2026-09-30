package com.growshopsluis.lightmeter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LampClassifierTest {
    @Test
    public void recognizesPurpleGrowLight() {
        assertEquals(LampClassifier.Type.BLURPLE_LED,
                LampClassifier.classify(0.8, 0.4, 0.7).type);
    }

    @Test
    public void recognizesWarmHpsLight() {
        assertEquals(LampClassifier.Type.HPS,
                LampClassifier.classify(0.9, 0.65, 0.3).type);
    }

    @Test
    public void recognizesCoolFluorescentLight() {
        assertEquals(LampClassifier.Type.FLUORESCENT,
                LampClassifier.classify(0.4, 0.6, 0.8).type);
    }

    @Test
    public void defaultsNeutralLightToWhiteLed() {
        assertEquals(LampClassifier.Type.WHITE_LED,
                LampClassifier.classify(0.7, 0.72, 0.69).type);
    }

    @Test
    public void recognizesNeutralFlickeringTube() {
        assertEquals(LampClassifier.Type.FLUORESCENT,
                LampClassifier.classify(0.62, 0.63, 0.61, 900, 0.04).type);
    }

    @Test
    public void keepsBrightStableWhiteSourceAsLed() {
        assertEquals(LampClassifier.Type.WHITE_LED,
                LampClassifier.classify(0.62, 0.63, 0.61, 20_000, 0.005).type);
    }
}
