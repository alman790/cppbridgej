# Security Policy

CppBridgeJ loads native shared libraries and executes exported functions in the current JVM process. Treat native libraries as trusted code.

## Supported Versions

Security fixes are accepted for the latest released version and the current `main` branch.

## Reporting a Vulnerability

Please do not open public issues for suspected vulnerabilities. Report privately through GitHub Security Advisories when available, or contact the maintainer listed in the repository metadata.

Include:

- affected version or commit;
- operating system, JDK, and compiler;
- minimal Java interface and native function signature;
- reproduction steps and observed impact.

## Security Boundary

CppBridgeJ does not sandbox native code. A loaded library can read process memory, crash the JVM, perform I/O, and call operating-system APIs with the permissions of the Java process.
