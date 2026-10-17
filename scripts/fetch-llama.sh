#!/usr/bin/env bash
set -euo pipefail
mkdir -p vendor
if [ -d vendor/llama.cpp/examples/llama.android/lib ]; then
  echo 'llama.cpp Android lib exists.'; exit 0
fi
# Pin the chosen upstream revision by setting LLAMA_REF to a known SHA for reproducible releases.
LLAMA_REF="${LLAMA_REF:-master}"
git clone --depth 1 --branch "$LLAMA_REF" https://github.com/ggml-org/llama.cpp.git vendor/llama.cpp || {
  rm -rf vendor/llama.cpp
  git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git vendor/llama.cpp
  (cd vendor/llama.cpp && git checkout "$LLAMA_REF")
}
(cd vendor/llama.cpp && git rev-parse HEAD) > vendor/llama-ref.txt
