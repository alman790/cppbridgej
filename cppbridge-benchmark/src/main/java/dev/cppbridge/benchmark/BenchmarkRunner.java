package dev.cppbridge.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.CommandLineOptionException;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/** Command-line entry point for running the benchmark JAR. */
public final class BenchmarkRunner {
    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws RunnerException, CommandLineOptionException, java.io.IOException {
        CommandLineOptions commandLine = new CommandLineOptions(args);
        if (commandLine.shouldHelp() || commandLine.shouldList()
                || commandLine.shouldListWithParams() || commandLine.shouldListProfilers()
                || commandLine.shouldListResultFormats()) {
            org.openjdk.jmh.Main.main(args);
            return;
        }
        Options options = new OptionsBuilder()
                .parent(commandLine)
                .detectJvmArgs()
                .jvmArgsAppend("--enable-native-access=ALL-UNNAMED")
                .build();

        new Runner(options).run();
    }
}
