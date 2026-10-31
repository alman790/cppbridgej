package dev.cppbridge.voxel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure-Java upload scheduler: no GL, native state, or off-heap allocations. */
final class MeshUploadBudget {
    record Candidate(int cx,int cz,int revision,int quads,boolean missing) {
        long estimatedBytes() {
            // The opaque quad uses two packed 32-bit words. Include a small
            // reserve for the once-uploaded coarse mesh; fluid is not included.
            return (long)quads*8+(missing?4096:0);
        }
    }
    private MeshUploadBudget() {}
    static List<Candidate> select(List<Candidate> input,float eyeX,float eyeZ,
                                  float yaw,int maxChunks,long maxBytes) {
        if(maxChunks<=0||maxBytes<=0||input.isEmpty()) return List.of();
        float sx=(float)Math.sin(yaw),sz=(float)Math.cos(yaw);
        ArrayList<Candidate> sorted=new ArrayList<>(input);
        sorted.sort(Comparator.<Candidate>comparingDouble(c -> {
            float dx=c.cx()*16+8-eyeX,dz=c.cz()*16+8-eyeZ;
            float d2=dx*dx+dz*dz;
            float forward=dx*sx+dz*sz;
            // Distance dominates; prefer forward chunks at equal distances.
            return d2-Math.min(128.0f,Math.max(-128.0f,forward*1.5f));
        }).thenComparingInt(Candidate::cx).thenComparingInt(Candidate::cz));
        ArrayList<Candidate> selected=new ArrayList<>(Math.min(maxChunks,sorted.size()));
        long used=0;
        for(Candidate candidate:sorted) {
            if(selected.size()==maxChunks) break;
            long size=candidate.estimatedBytes();
            // A large single upload must not starve indefinitely.
            if(!selected.isEmpty() && size>maxBytes-used) continue;
            if(selected.isEmpty() || size<=maxBytes-used) {
                selected.add(candidate);
                used+=size;
            }
        }
        return selected;
    }
}
