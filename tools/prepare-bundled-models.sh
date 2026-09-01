#!/usr/bin/env bash

set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$REPO_ROOT/app/src/main/assets/models"
SEGMENTER="$ASSET_DIR/comic-text-segmenter-512.onnx"
AOT="$ASSET_DIR/aot-inpainting.onnx"
HF_ENDPOINT="${MASUMI_HF_ENDPOINT:-https://huggingface.co}"

SEGMENT_SOURCE_URL="https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx"
SEGMENT_SOURCE_BYTES=94669756
SEGMENT_BYTES=65568382
AOT_URL="$HF_ENDPOINT/lemondouble/lemon-manga-translator/resolve/e8c08f38f188db684fdc32c4cf88627c7df92096/onnx/aot-inpainting/aot_folded.onnx"
AOT_BYTES=23009155

valid_file() {
  local path="$1"
  local expected_bytes="$2"
  [ -f "$path" ] &&
    [ "$(wc -c <"$path" | tr -d '[:space:]')" = "$expected_bytes" ]
}

require_file() {
  valid_file "$1" "$2" || {
    echo "Downloaded model has an unexpected byte length." >&2
    exit 1
  }
}

command -v curl >/dev/null 2>&1 || {
  echo "curl is required to prepare bundled models." >&2
  exit 1
}
mkdir -p "$ASSET_DIR"
download_dir="$(mktemp -d /tmp/masumi-models.XXXXXX)"
segment_source="$download_dir/comictextdetector.pt.onnx"
segment_output="$download_dir/comic-text-segmenter-512.onnx"
aot_output="$download_dir/aot-inpainting.onnx"

cleanup_temporary_files() {
  rm -f -- "$segment_source" "$segment_output" "$aot_output"
  rmdir "$download_dir" >/dev/null 2>&1 || true
}
trap cleanup_temporary_files EXIT

if ! valid_file "$SEGMENTER" "$SEGMENT_BYTES"; then
  python3 -c 'import onnx; assert onnx.__version__ == "1.22.0"' >/dev/null 2>&1 || {
    echo "Python package onnx==1.22.0 is required to derive the segmenter." >&2
    exit 1
  }
  curl --fail --location --retry 3 "$SEGMENT_SOURCE_URL" --output "$segment_source"
  require_file "$segment_source" "$SEGMENT_SOURCE_BYTES"
  python3 "$REPO_ROOT/tools/build-comic-text-segmenter.py" "$segment_source" "$segment_output"
  require_file "$segment_output" "$SEGMENT_BYTES"
  mv "$segment_output" "$SEGMENTER"
fi

if ! valid_file "$AOT" "$AOT_BYTES"; then
  curl --fail --location --retry 3 "$AOT_URL" --output "$aot_output"
  require_file "$aot_output" "$AOT_BYTES"
  mv "$aot_output" "$AOT"
fi

chmod 600 "$SEGMENTER" "$AOT"
echo "Bundled model assets are present with their expected byte lengths."
