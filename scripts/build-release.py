#!/usr/bin/env python3
"""Build a reproducible library-only JAR. Application trees are never traversed."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = (ROOT / 'version.txt').read_text().strip()
assert re.fullmatch(r'\d+\.\d+\.\d+', VERSION), VERSION
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ET.parse(ROOT / 'pom.xml')
assert pom.findtext('m:version', namespaces=NS) == VERSION
# Explicit allowlist prevents dev/journal dependencies from leaking through deps.edn.
deps = {(d.findtext('m:groupId', namespaces=NS), d.findtext('m:artifactId', namespaces=NS))
        for d in pom.findall('m:dependencies/m:dependency', NS)}
assert deps == {('org.clojure', 'clojure'), ('org.clojure', 'data.json')}, deps
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
entries = {}
for platform, suffix in [('clj', '.clj'), ('cljc', '.cljc'), ('cljs', '.cljs')]:
    directory = ROOT / 'src' / platform / 'clj_manifold3d'
    for source in sorted(directory.glob('*' + suffix)):
        if source.stem == 'flag_example':
            continue
        assert not source.name.startswith('.')
        entries['clj_manifold3d/' + source.name] = source.read_bytes()
for license in sorted((ROOT / 'release-licenses').glob('*.txt')):
    entries['META-INF/licenses/' + license.name] = license.read_bytes()
entries['deps.cljs'] = b'{:npm-deps {"fast-png" "6.4.0"}}\n'
# Native JS/WASM are built separately from the pinned matching Manifold revision.
for source, destination in [('public/wasm/manifold.js', 'manifold.js'),
                            ('public/wasm/manifold.wasm', 'manifold.wasm')]:
    entries['clj_manifold3d/wasm/' + destination] = (ROOT / source).read_bytes()
meta = 'META-INF/maven/org.clojars.cartesiantheatrics/clj-manifold3d/'
entries[meta + 'pom.xml'] = (ROOT / 'pom.xml').read_bytes()
entries[meta + 'pom.properties'] = f'groupId=org.clojars.cartesiantheatrics\nartifactId=clj-manifold3d\nversion={VERSION}\n'.encode()
entries['META-INF/MANIFEST.MF'] = f'Manifest-Version: 1.0\r\nImplementation-Version: {VERSION}\r\nSCM-Revision: {revision}\r\n\r\n'.encode()
assert not any('journal' in name or 'try_it' in name for name in entries)
for required in ['core.cljc', 'core.cljs', 'animation.clj', 'animation.cljs', 'upstream.cljc']:
    assert 'clj_manifold3d/' + required in entries
output = ROOT / 'target' / f'clj-manifold3d-{VERSION}.jar'
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
    for name, content in sorted(entries.items()):
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        jar.writestr(info, content)
(ROOT / 'target' / f'clj-manifold3d-{VERSION}.pom').write_bytes((ROOT / 'pom.xml').read_bytes())
print(f'Built {output}: {len(entries)} entries; journal and application trees excluded')
