package com.growshopsluis.lightmeter;

public final class LightCalculations {
    private LightCalculations() {}

    public static double estimateLux(
            long exposureTimeNs,
            int iso,
            float aperture,
            double normalizedLuma,
            double calibration) {
        if (exposureTimeNs <= 0 || iso <= 0 || aperture <= 0 || normalizedLuma <= 0) {
            return 0;
        }
        double exposureSeconds = exposureTimeNs / 1_000_000_000.0;
        double ev100 = log2((aperture * aperture / exposureSeconds) * (100.0 / iso));
        double lumaCorrection = clamp(normalizedLuma / 0.18, 0.25, 4.0);
        return Math.max(0, 2.5 * Math.pow(2, ev100) * lumaCorrection * calibration);
    }

    public static double estimatePpfd(double lux, double micromolesPerLux) {
        return Math.max(0, lux * micromolesPerLux);
    }

    public static double estimateLumens(double lux, double areaSquareMeters) {
        return Math.max(0, lux * areaSquareMeters);
    }

    public static double estimateProjectedDli(double ppfd, double photoperiodHours) {
        return Math.max(0, ppfd * photoperiodHours * 0.0036);
    }

    public static double luxToFootCandles(double lux) {
        return Math.max(0, lux / 10.7639);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
