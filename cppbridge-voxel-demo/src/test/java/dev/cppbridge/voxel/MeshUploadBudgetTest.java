package dev.cppbridge.voxel;

import java.util.List;

public final class MeshUploadBudgetTest {
    public static void main(String[] args) {
        var ahead=new MeshUploadBudget.Candidate(0,1,2,10,true);
        var behind=new MeshUploadBudget.Candidate(0,-1,2,10,true);
        var distant=new MeshUploadBudget.Candidate(4,4,2,10,true);
        var jobs=MeshUploadBudget.select(List.of(distant,behind,ahead),8,8,0,2,9000);
        if(!jobs.equals(List.of(ahead,behind))) throw new AssertionError(jobs);
        var large=new MeshUploadBudget.Candidate(0,0,2,300_000,false);
        if(!MeshUploadBudget.select(List.of(large),8,8,0,2,1024).equals(List.of(large)))
            throw new AssertionError("Oversized upload starved");
        if(!MeshUploadBudget.select(List.of(ahead,behind),8,8,0,0,9999).isEmpty())
            throw new AssertionError("Disabled budget scheduled uploads");
        if(!MeshUploadBudget.select(List.of(ahead,behind),8,8,0,2,1).equals(List.of(ahead)))
            throw new AssertionError("Multiple oversized uploads in one frame");
        System.out.println("Stratawild 0.9: upload prioritization and budgets: OK");
    }
}
