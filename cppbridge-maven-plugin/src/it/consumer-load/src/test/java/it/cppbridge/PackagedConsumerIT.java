package it.cppbridge;

import dev.cppbridge.CppBridge;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class PackagedConsumerIT {
    @Test
    void runsUsingOnlyJarsFromAnUnrelatedWorkingDirectory() throws Exception {
        Path jar = Path.of("target/consumer-load-1.0.0.jar").toAbsolutePath();
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertTrue(archive.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/cppbridge/")),
                    "The native library must be included in the published JAR");
        }
        Path core = Path.of(CppBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path workingDirectory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "consumer run ");
        Path log = workingDirectory.resolve("output.txt");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", jar + File.pathSeparator + core,
                "it.cppbridge.ConsumerApp")
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Packaged consumer did not exit");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("packaged consumer ok"), output);
        } finally {
            process.destroyForcibly();
        }
    }
}
