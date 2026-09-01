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
SEGMENT_SOURCE_SHA="1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f"
SEGMENT_BYTES=65568382
SEGMENT_SHA="688cb2b55bc14e29957bb4dad768e7420a4b1f740b84ffadc83ecaac63846485"
AOT_URL="$HF_ENDPOINT/lemondouble/lemon-manga-translator/resolve/e8c08f38f188db684fdc32c4cf88627c7df92096/onnx/aot-inpainting/aot_folded.onnx"
AOT_BYTES=23009155
AOT_SHA="e0d8f438ca9567eccc9d358963427601b6f64a650cbe6189ec82fc43830a0390"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

valid_file() {
  local path="$1"
  local expected_bytes="$2"
  local expected_sha="$3"
  [ -f "$path" ] &&
    [ "$(wc -c <"$path" | tr -d '[:space:]')" = "$expected_bytes" ] &&
    [ "$(sha256_file "$path")" = "$expected_sha" ]
}

require_file() {
  valid_file "$1" "$2" "$3" || {
    echo "Downloaded model failed its pinned size or SHA-256 check." >&2
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

if ! valid_file "$SEGMENTER" "$SEGMENT_BYTES" "$SEGMENT_SHA"; then
  python3 -c 'import onnx; assert onnx.__version__ == "1.22.0"' >/dev/null 2>&1 || {
    echo "Python package onnx==1.22.0 is required to derive the segmenter." >&2
    exit 1
  }
  curl --fail --location --retry 3 "$SEGMENT_SOURCE_URL" --output "$segment_source"
  require_file "$segment_source" "$SEGMENT_SOURCE_BYTES" "$SEGMENT_SOURCE_SHA"
  python3 "$REPO_ROOT/tools/build-comic-text-segmenter.py" "$segment_source" "$segment_output"
  require_file "$segment_output" "$SEGMENT_BYTES" "$SEGMENT_SHA"
  mv "$segment_output" "$SEGMENTER"
fi

if ! valid_file "$AOT" "$AOT_BYTES" "$AOT_SHA"; then
  curl --fail --location --retry 3 "$AOT_URL" --output "$aot_output"
  require_file "$aot_output" "$AOT_BYTES" "$AOT_SHA"
  mv "$aot_output" "$AOT"
fi

chmod 600 "$SEGMENTER" "$AOT"
echo "Bundled model assets are present and match their pinned digests."
