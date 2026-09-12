package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CommandRunner {
    private CommandRunner() {
    }

    static CommandResult run(List<String> command, Path workingDirectory, long timeoutSeconds)
            throws MojoExecutionException {
        if (timeoutSeconds <= 0) {
            throw new MojoExecutionException("commandTimeoutSeconds must be greater than zero");
        }
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("cppbridge-command-", ".log");
            process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new MojoExecutionException("Command timed out after " + timeoutSeconds + " seconds: "
                        + String.join(" ", command) + "\n" + Files.readString(output, StandardCharsets.UTF_8));
            }
            return new CommandResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new MojoExecutionException("Cannot execute command: " + String.join(" ", command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Command interrupted: " + String.join(" ", command), exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException exception) {
                    output.toFile().deleteOnExit();
                }
            }
        }
    }
}
