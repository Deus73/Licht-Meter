package com.growshopsluis.lightmeter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class LightSampleWindow {
    private static final long WINDOW_MS = 3_000;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public synchronized Snapshot add(long timestamp, double value) {
        if (!samples.isEmpty() && timestamp < samples.getLast().timestamp) samples.clear();
        if (Double.isFinite(value) && value > 0) samples.addLast(new Sample(timestamp, value));
        while (!samples.isEmpty() && samples.getFirst().timestamp < timestamp - WINDOW_MS) {
            samples.removeFirst();
        }
        return snapshot();
    }

    public synchronized void clear() {
        samples.clear();
    }

    private Snapshot snapshot() {
        if (samples.isEmpty()) return Snapshot.EMPTY;
        double[] values = new double[samples.size()];
        int index = 0;
        for (Sample sample : samples) values[index++] = sample.value;
        Arrays.sort(values);
        int trim = values.length >= 10 ? Math.max(1, values.length / 10) : 0;
        int start = trim;
        int end = values.length - trim;
        double total = 0;
        for (int i = start; i < end; i++) total += values[i];
        double average = total / (end - start);
        double variance = 0;
        for (int i = start; i < end; i++) {
            double difference = values[i] - average;
            variance += difference * difference;
        }
        double relativeDeviation = average == 0 ? 1
                : Math.sqrt(variance / (end - start)) / average;
        long duration = samples.getLast().timestamp - samples.getFirst().timestamp;
        boolean stable = values.length >= 8 && duration >= 2_000 && relativeDeviation <= 0.08;
        return new Snapshot(average, values[0], values[values.length - 1],
                relativeDeviation, duration, values.length, stable);
    }

    private static final class Sample {
        final long timestamp;
        final double value;

        Sample(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 1, 0, 0, false);
        public final double average;
        public final double minimum;
        public final double maximum;
        public final double relativeDeviation;
        public final long durationMs;
        public final int sampleCount;
        public final boolean stable;

        Snapshot(double average, double minimum, double maximum, double relativeDeviation,
                long durationMs, int sampleCount, boolean stable) {
            this.average = average;
            this.minimum = minimum;
            this.maximum = maximum;
            this.relativeDeviation = relativeDeviation;
            this.durationMs = durationMs;
            this.sampleCount = sampleCount;
            this.stable = stable;
        }
    }
}
