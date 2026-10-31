package dev.cppbridge.voxel;

public final class FrameTimeStatsTest {
    private static void eq(double expected,double actual) {
        if (Math.abs(expected-actual) > 1e-9) throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args) {
        FrameTimeStats s = new FrameTimeStats(100);
        eq(0,s.p95Ms());
        for (int i=1;i<=100;i++) s.addNanos(i*1_000_000L);
        eq(95,s.p95Ms());eq(99,s.p99Ms());
        s.addNanos(1_000_000L); // overwrites oldest ring sample
        eq(95,s.p95Ms());eq(99,s.p99Ms());
        if(s.count()!=100) throw new AssertionError("ring capacity");
        boolean threw=false;
        try {s.percentileMs(0);} catch(IllegalArgumentException e) {threw=true;}
        if(!threw) throw new AssertionError("invalid percentile accepted");
        System.out.println("FrameTimeStats p95/p99/ring: OK");
    }
}
