#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
KWS_MODEL=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download

aar=spike/libs/sherpa-onnx-$SHERPA_VERSION.aar
if [ ! -f "$aar" ]; then
  curl -fL -o "$aar" "$BASE/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
  zip -q -d "$aar" 'jni/*/libonnxruntime.so'
fi

kws=spike/src/main/assets/kws
if [ ! -f "$kws/tokens.txt" ]; then
  tmp=$(mktemp -d)
  curl -fL "$BASE/kws-models/$KWS_MODEL.tar.bz2" | tar xj -C "$tmp"
  mkdir -p "$kws"
  src="$tmp/$KWS_MODEL"
  cp "$src/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$kws/encoder.onnx"
  cp "$src/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$kws/decoder.onnx"
  cp "$src/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$kws/joiner.onnx"
  cp "$src/tokens.txt" "$src/bpe.model" "$kws/"
  mkdir -p build/test-wavs && cp "$src"/test_wavs/*.wav build/test-wavs/
  rm -rf "$tmp"
fi
ASR_MODEL=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
asr=spike/src/main/assets/asr
if [ ! -f "$asr/tokens.txt" ]; then
  tmp=$(mktemp -d)
  curl -fL "$BASE/asr-models/$ASR_MODEL.tar.bz2" | tar xj -C "$tmp"
  mkdir -p "$asr"
  src="$tmp/$ASR_MODEL"
  cp "$src/encoder-epoch-99-avg-1.int8.onnx" "$asr/encoder.onnx"
  cp "$src/decoder-epoch-99-avg-1.int8.onnx" "$asr/decoder.onnx"
  cp "$src/joiner-epoch-99-avg-1.int8.onnx" "$asr/joiner.onnx"
  cp "$src/tokens.txt" "$asr/"
  rm -rf "$tmp"
fi
oww=spike/src/main/assets/oww
if [ ! -f "$oww/embedding_model.onnx" ]; then
  mkdir -p "$oww"
  for m in melspectrogram embedding_model; do
    curl -fL -o "$oww/$m.onnx" "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$m.onnx"
  done
fi
echo "deps ready"
