package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    @TempDir
    Path directory;

    @Test
    void capturesCompilerOutputAndExitStatus() throws Exception {
        CommandResult result = CommandRunner.run(command("fail"), directory, 10);
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains("compiler diagnostic"));
        assertTrue(result.output().contains(directory.toString()));
    }

    @Test
    void terminatesCommandsThatExceedTheTimeout() {
        MojoExecutionException error = assertThrows(MojoExecutionException.class,
                () -> CommandRunner.run(command("wait"), directory, 1));
        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    void reportsMissingExecutablesAndInvalidTimeouts() {
        assertThrows(MojoExecutionException.class,
                () -> CommandRunner.run(List.of(directory.resolve("missing-compiler").toString()), directory, 10));
        assertThrows(MojoExecutionException.class, () -> CommandRunner.run(command("fail"), directory, 0));
    }

    @Test
    void preservesInterruptStatus() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(MojoExecutionException.class, () -> CommandRunner.run(command("wait"), directory, 10));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private List<String> command(String action) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), action);
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("wait")) {
                Thread.sleep(60_000);
            }
            System.err.println("compiler diagnostic");
            System.out.println(Path.of("").toAbsolutePath());
            System.exit(7);
        }
    }
}
