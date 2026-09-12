#!/usr/bin/env python3
"""Compile the documented quickstart using only the release Maven bundle."""
import json
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
version = json.loads((root / '.github/release.json').read_text())['version']
quickstart = (root / 'docs/QUICKSTART.md').read_text()
with tempfile.TemporaryDirectory(prefix='cppbridge-consumer-') as temporary:
    directory = Path(temporary)
    repository = directory / 'repository'
    with ZipFile(root / f'target/release/cppbridgej-{version}-maven.zip') as archive:
        archive.extractall(repository)
    files = [('pom.xml', 'xml', 0), ('src/main/cpp/fastmath.cpp', 'cpp', 0),
             ('src/main/java/example/FastMath.java', 'java', 0),
             ('src/main/java/example/Main.java', 'java', 1)]
    for name, language, index in files:
        content = re.findall(r'```' + language + r'\n(.*?)```', quickstart, re.S)[index]
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    result = subprocess.run([str(root / 'mvnw'), '-B', '-ntp', '-f', str(directory / 'pom.xml'),
                             f'-Dmaven.repo.local={repository}', 'package', 'exec:exec'],
                            cwd=directory, capture_output=True, text=True, timeout=300)
    print(result.stdout)
    print(result.stderr)
    result.check_returncode()
    if not re.search(r'^20\.0\s*$', result.stdout, re.M):
        raise AssertionError('Quickstart did not produce 20.0')
print('Release Maven bundle consumer passed')
