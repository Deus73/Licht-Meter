package com.growshopsluis.lightmeter;

public final class LampClassifier {
    public enum Type {
        WHITE_LED(0.015),
        BLURPLE_LED(0.025),
        HPS(0.012),
        FLUORESCENT(0.013);

        public final double ppfdFactor;

        Type(double ppfdFactor) {
            this.ppfdFactor = ppfdFactor;
        }
    }

    public static final class Result {
        public final Type type;
        public final int confidencePercent;

        Result(Type type, int confidencePercent) {
            this.type = type;
            this.confidencePercent = confidencePercent;
        }
    }

    private LampClassifier() {}

    public static Result classify(double red, double green, double blue) {
        return classify(red, green, blue, Double.NaN, 0);
    }

    public static Result classify(
            double red, double green, double blue, double lux, double flickerRatio) {
        double safeRed = Math.max(0.01, red);
        double safeGreen = Math.max(0.01, green);
        double safeBlue = Math.max(0.01, blue);
        double redToGreen = safeRed / safeGreen;
        double blueToGreen = safeBlue / safeGreen;
        double redToBlue = safeRed / safeBlue;

        if (redToGreen > 1.28 && blueToGreen > 1.18) {
            int confidence = confidence(65 + (redToGreen - 1.28) * 35
                    + (blueToGreen - 1.18) * 35);
            return new Result(Type.BLURPLE_LED, confidence);
        }
        if (redToBlue > 1.55 && safeGreen / safeBlue > 1.12) {
            int confidence = confidence(65 + (redToBlue - 1.55) * 25);
            return new Result(Type.HPS, confidence);
        }
        if (safeBlue / safeRed > 1.28 && blueToGreen > 1.08) {
            int confidence = confidence(60 + (safeBlue / safeRed - 1.28) * 30);
            return new Result(Type.FLUORESCENT, confidence);
        }
        boolean greenPeak = safeGreen / safeRed > 1.06 && safeGreen / safeBlue > 1.03;
        boolean lowOutputWhiteSource = !Double.isNaN(lux) && lux > 0 && lux < 2_500
                && redToGreen < 1.12 && blueToGreen < 1.18;
        if (greenPeak || (lowOutputWhiteSource && flickerRatio > 0.012)) {
            int confidence = confidence(62 + Math.min(18, flickerRatio * 250)
                    + (greenPeak ? 8 : 0));
            return new Result(Type.FLUORESCENT, confidence);
        }
        return new Result(Type.WHITE_LED, 55);
    }

    private static int confidence(double confidence) {
        return (int) Math.max(50, Math.min(95, Math.round(confidence)));
    }
}
