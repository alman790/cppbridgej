package dev.cppbridge.runtime;

import java.util.Locale;

/**
 * Operating systems supported by the native backend.
 */
public enum NativePlatform {
    MACOS,
    LINUX,
    WINDOWS;

    /**
     * Detects the current operating system from {@code os.name}.
     *
     * @return detected platform
     */
    public static NativePlatform detect() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return MACOS;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }

    /**
     * Converts a logical library name to a platform-specific shared-library
     * filename.
     *
     * @param baseName logical library name without prefix or extension
     * @return platform-specific library filename
     */
    public String libraryFileName(String baseName) {
        String cleanName = baseName
                .replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("-", "_");

        return switch (this) {
            case MACOS -> "lib" + cleanName + ".dylib";
            case LINUX -> "lib" + cleanName + ".so";
            case WINDOWS -> cleanName + ".dll";
        };
    }

    /**
     * Returns the classpath directory used for libraries for the current CPU.
     *
     * @return resource directory, such as {@code META-INF/cppbridge/linux-x86_64}
     */
    public String resourceDirectory() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String normalized = switch (arch) {
            case "amd64", "x64", "x86_64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> arch.replaceAll("[^a-z0-9_]", "_");
        };
        return "META-INF/cppbridge/" + name().toLowerCase(Locale.ROOT) + "-" + normalized;
    }
}
