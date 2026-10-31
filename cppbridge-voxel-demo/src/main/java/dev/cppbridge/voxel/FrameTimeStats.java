package dev.cppbridge.voxel;

import java.util.Arrays;

/** Bounded, allocation-free sampling in the render loop. Percentiles use
 * nearest-rank, reported only once per title update (not per frame). */
public final class FrameTimeStats {
    private final long[] samples;
    private int count, next;

    public FrameTimeStats(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity must be >= 2");
        samples = new long[capacity];
    }

    public void addNanos(long frameNanos) {
        if (frameNanos < 0) throw new IllegalArgumentException("negative frame time");
        samples[next] = frameNanos;
        next = (next + 1) % samples.length;
        if (count < samples.length) count++;
    }

    public int count() {return count;}

    public double percentileMs(double p) {
        if (p <= 0 || p > 1 || Double.isNaN(p))
            throw new IllegalArgumentException("percentile must lie in (0,1]");
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int rank = Math.max(1, (int)Math.ceil(p * count));
        return sorted[rank - 1] / 1_000_000.0;
    }

    public double p95Ms() {return percentileMs(.95);}
    public double p99Ms() {return percentileMs(.99);}
}
