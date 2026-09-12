package it.cppbridge;

import dev.cppbridge.CppBridge;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;

import java.util.Arrays;

public final class ConsumerApp {
    public static void main(String[] args) {
        var report = CppBridge.inspect(ConsumerApi.class);
        if (!report.isHealthy()) {
            throw new AssertionError(report.toText());
        }
        ConsumerApi api = CppBridge.load(ConsumerApi.class);
        if (api.answer() != 123) {
            throw new AssertionError("Unexpected native result");
        }
        int[] values = {1, 2, 3};
        api.addEach(values, 10);
        if (!Arrays.equals(new int[]{11, 12, 13}, values)) {
            throw new AssertionError(Arrays.toString(values));
        }
        System.out.println("packaged consumer ok");
    }

    @CppModule(libraryName = "consumer")
    public interface ConsumerApi {
        @CppFunction("answer_value")
        int answer();

        @CppFunction("add_each")
        void addEach(int[] values, int delta);
    }
}
