#!/usr/bin/env python3
"""Build versioned release assets from verified Maven output."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def release_metadata():
    metadata = json.loads((ROOT / ".github/release.json").read_text())
    version = ET.parse(ROOT / "pom.xml").findtext("m:version", namespaces=NS)
    if version != metadata["version"] or "SNAPSHOT" in version:
        raise ValueError("Release metadata must match a non-SNAPSHOT Maven version")
    if not (ROOT / metadata["notes"]).is_file():
        raise ValueError("Release notes are missing")
    return metadata


def require(path):
    if not path.is_file() or path.stat().st_size == 0:
        raise FileNotFoundError(f"Missing release artifact: {path}")
    return path


def add(zip_file, source, name):
    zip_file.write(require(source), name)


def platform_bundle(version):
    example = ROOT / f"cppbridge-example/target/cppbridge-example-{version}.jar"
    benchmark = ROOT / "cppbridge-benchmark/target/benchmarks.jar"
    core = ROOT / f"cppbridge-core/target/cppbridge-core-{version}.jar"
    with ZipFile(require(example)) as jar:
        classifiers = {name.split('/')[2] for name in jar.namelist()
                       if name.startswith("META-INF/cppbridge/")
                       and name.endswith((".so", ".dylib", ".dll"))}
    if len(classifiers) != 1:
        raise ValueError(f"Expected one example platform, found {classifiers}")
    classifier = classifiers.pop()
    with ZipFile(require(benchmark)) as jar:
        if not any(name.startswith(f"META-INF/cppbridge/{classifier}/")
                   and name.endswith((".so", ".dylib", ".dll")) for name in jar.namelist()):
            raise ValueError("Example and benchmark platforms differ")
    output = ROOT / "target/platform-release"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"cppbridgej-{version}-{classifier}.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as jar:
        add(jar, core, f"lib/{core.name}")
        add(jar, example, f"lib/{example.name}")
        add(jar, benchmark, "lib/benchmarks.jar")
        add(jar, ROOT / "LICENSE", "LICENSE")
        jar.writestr("README.txt", f"CppBridgeJ {version} ({classifier})\n\n"
                     "Requires JDK 22 or newer. The native libraries are included.\n"
                     "Run the example from this directory:\n"
                     f"java --enable-native-access=ALL-UNNAMED -cp \"lib/*\" dev.cppbridge.example.Main\n\n"
                     "List benchmarks:\n"
                     "java --enable-native-access=ALL-UNNAMED -jar lib/benchmarks.jar -l\n")
    print(archive)
    return archive


def smoke_bundle(archive):
    # The extracted bundle must run without access to Maven or target/native.
    with tempfile.TemporaryDirectory(prefix="cppbridge-release-") as temporary:
        directory = Path(temporary)
        with ZipFile(archive) as jar:
            jar.extractall(directory)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        result = subprocess.run([java, "--enable-native-access=ALL-UNNAMED", "-cp", "lib/*",
                                 "dev.cppbridge.example.Main"], cwd=directory,
                                capture_output=True, text=True, timeout=30, check=True)
        for expected in ("Healthy: true", "sum(10, 20) = 30", "average = 25.0",
                         "after brightenNative = [30, 120, 255]"):
            if expected not in result.stdout:
                raise AssertionError(result.stdout + result.stderr)
        result = subprocess.run([java, "--enable-native-access=ALL-UNNAMED", "-jar",
                                 "lib/benchmarks.jar", "-l"], cwd=directory,
                                capture_output=True, text=True, timeout=30, check=True)
        if "ArrayBenchmarks" not in result.stdout:
            raise AssertionError(result.stdout + result.stderr)
    print("Platform bundle smoke test passed")


def assemble(version):
    output = ROOT / "target/release"
    output.mkdir(parents=True, exist_ok=True)
    maven = output / f"cppbridgej-{version}-maven.zip"
    with ZipFile(maven, "w", ZIP_DEFLATED) as jar:
        add(jar, ROOT / "pom.xml",
            f"dev/cppbridge/cppbridgej-parent/{version}/cppbridgej-parent-{version}.pom")
        shutil.copyfile(ROOT / "pom.xml", output / f"cppbridgej-parent-{version}.pom")
        for module in ("cppbridge-core", "cppbridge-maven-plugin"):
            prefix = f"dev/cppbridge/{module}/{version}"
            add(jar, ROOT / module / "pom.xml", f"{prefix}/{module}-{version}.pom")
            shutil.copyfile(ROOT / module / "pom.xml", output / f"{module}-{version}.pom")
            for suffix in ("", "-sources", "-javadoc"):
                name = f"{module}-{version}{suffix}.jar"
                source = ROOT / module / "target" / name
                add(jar, source, f"{prefix}/{name}")
                shutil.copyfile(source, output / name)
    source = require(ROOT / "target/cppbridgej-source.zip")
    with ZipFile(source) as jar:
        archived_version = ET.fromstring(jar.read("pom.xml")).findtext("m:version", namespaces=NS)
        if archived_version != version:
            raise ValueError("Source archive version differs from the binary version")
        if any("target" in Path(name).parts or ".git" in Path(name).parts for name in jar.namelist()):
            raise ValueError("Source archive contains build output or Git internals")
    shutil.copyfile(source, output / f"cppbridgej-{version}-source.zip")
    bundles = list(output.glob(f"cppbridgej-{version}-*.zip"))
    for platform in ("linux-", "macos-", "windows-"):
        if not any(path.name.startswith(f"cppbridgej-{version}-{platform}") for path in bundles):
            raise ValueError(f"Missing verified {platform} platform bundle")
    lines = []
    for path in sorted(output.iterdir()):
        if path.is_file() and path.name != "SHA256SUMS":
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
    (output / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")
    print(f"Prepared {len(lines)} release assets and SHA256SUMS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("platform", "assemble"))
    args = parser.parse_args()
    version = release_metadata()["version"]
    if args.mode == "platform":
        smoke_bundle(platform_bundle(version))
    else:
        assemble(version)


if __name__ == "__main__":
    main()
