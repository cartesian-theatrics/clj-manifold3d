#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifold_dir="${MANIFOLD_SOURCE:-${repo_dir}/../manifold}"
build_dir="${MANIFOLD_WASM_BUILD:-${manifold_dir}/build-cljs}"
options=()
# Reuse downloaded source dependencies, never native object files.
for dependency in texttopolygon freetype2; do
  source_dir="${manifold_dir}/build/_deps/${dependency}-src"
  if [[ -d "$source_dir" ]]; then
    options+=("-DFETCHCONTENT_SOURCE_DIR_${dependency^^}=${source_dir}")
  fi
done

emcmake cmake -S "$manifold_dir" -B "$build_dir" \
  -DCMAKE_BUILD_TYPE=Release -DMANIFOLD_JSBIND=ON -DMANIFOLD_JS_ESM=OFF \
  -DMANIFOLD_TEST=OFF -DMANIFOLD_CBIND=OFF -DMANIFOLD_PYBIND=OFF \
  -DMANIFOLD_PAR=OFF -DMANIFOLD_EXPORT=OFF -DCMAKE_CXX_FLAGS=-fexceptions \
  "${options[@]}"
cmake --build "$build_dir" --target manifoldjs --parallel "${BUILD_JOBS:-4}"
mkdir -p "$repo_dir/public/wasm" "$repo_dir/target/wasm"
cp "$build_dir/bindings/wasm/manifold.js" "$repo_dir/public/wasm/manifold.js"
cp "$build_dir/bindings/wasm/manifold.wasm" "$repo_dir/public/wasm/manifold.wasm"
cp "$build_dir/bindings/wasm/manifold.js" "$repo_dir/target/wasm/manifold.cjs"
cp "$build_dir/bindings/wasm/manifold.wasm" "$repo_dir/target/wasm/manifold.wasm"
printf 'Built fresh WASM bindings from %s\n' "$manifold_dir"
