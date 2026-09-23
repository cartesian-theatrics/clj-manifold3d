#!/usr/bin/env python3
"""Create a consumer project that resolves all library namespaces from the JAR."""
from pathlib import Path
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
version = (root / 'version.txt').read_text().strip()
out = root / 'target' / 'release-test'
if out.exists():
    shutil.rmtree(out)
out.mkdir()
for directory in ['test', 'examples', 'resources', 'scripts']:
    shutil.copytree(root / directory, out / directory, ignore=shutil.ignore_patterns('.*.swp'))
(out / 'node_modules').symlink_to(root / 'node_modules', target_is_directory=True)
(out / 'package.json').write_text((root / 'package.json').read_text())
jar = root / 'target' / f'clj-manifold3d-{version}.jar'
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert not any('journal' in name or 'try_it' in name for name in names)
    for directory in ['public/wasm', 'target/wasm']:
        (out / directory).mkdir(parents=True)
        for file in ['manifold.js', 'manifold.wasm']:
            destination = 'manifold.cjs' if directory == 'target/wasm' and file.endswith('.js') else file
            (out / directory / destination).write_bytes(archive.read('clj_manifold3d/wasm/' + file))
shutil.copyfile(root / 'public' / 'cljs-test.html', out / 'public' / 'cljs-test.html')
# Include the POM dependencies explicitly for :local/root JAR consumers.
(out / 'deps.edn').write_text('''{:paths ["test/shared" "test/cljs" "test/clj" "test/cljc" "examples" "resources"]
 :deps {org.clojars.cartesiantheatrics/clj-manifold3d {:local/root "../clj-manifold3d-''' + version + '''.jar"}
        org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/data.json {:mvn/version "2.4.0"}}
 :aliases {:native {:extra-deps {org.clojars.cartesiantheatrics/manifold3d$linux-x86_64 {:mvn/version "2.2.0"}}}
           :cljs-dev {:extra-deps {thheller/shadow-cljs {:mvn/version "2.22.9"}}}}}
''')
shutil.copyfile(root / 'shadow-cljs.edn', out / 'shadow-cljs.edn')
print(f'Prepared isolated consumer tests in {out}')
