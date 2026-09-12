# Publishing

## GitHub release

Use JDK 22 and the included Maven Wrapper. Run `./mvnw -B -Pcoverage -Dcppbridge.requireNativeCompiler=true clean verify` and `python scripts/package-release.py platform`. The latter extracts the distribution to a temporary directory and runs the packaged example without Maven or the source tree.

Update the root and module POM versions together, `.github/release.json`, the changelog, and the versioned notes under `docs/releases/`. Open a pull request and require green CI on Linux, macOS, Windows, and JDK 26. Commit messages use two lowercase words.

Merging a release manifest change to `main` starts the release workflow. A matching `v*` tag or manual workflow run also starts it. Published versions are skipped and never overwritten. The workflow checks the version, verifies the full reactor on three platforms, tests each platform bundle, and assembles Maven artifacts and a source archive from the release commit.

A draft release receives the assets and `SHA256SUMS`. The workflow downloads them again and verifies every checksum before making the release public. If interrupted, rerun the same workflow: only a draft belonging to the same commit can be resumed. Never move a published tag.

Source packaging uses `git archive HEAD` and rejects tracked worktree changes. Commit the release files before invoking `./scripts/package-source.sh`.

## Maven Central (pending)

The GitHub Maven ZIP is a local repository distribution, not proof of Central publication. Central requires verified access to the `dev.cppbridge` namespace, a Central Portal token, and GPG signing material. Keep credentials and keys outside the repository.

Configure Maven server `central` with the Portal token and configure GPG signing, then publish from the verified release commit:

```bash
./mvnw -B -Pcentral-publish -DskipTests deploy
```

Only the parent POM, `cppbridge-core`, and `cppbridge-maven-plugin` are deployable. The example and benchmark skip publication. Verify POMs, main JARs, sources, JavaDoc, and signatures in Central, then run a fresh consumer resolving both runtime and plugin before updating the installation instructions.
