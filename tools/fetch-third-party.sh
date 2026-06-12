#!/usr/bin/env bash
# Fetch the sherpa-onnx AAR and the Kokoro int8 model, and stage them where the
# build expects them. Neither is committed (size); this script is the record.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party app/libs app/src/main/assets/models
[ -f third_party/sherpa-onnx-1.13.2.aar ] || gh release download v1.13.2 -R k2-fsa/sherpa-onnx -p "sherpa-onnx-1.13.2.aar" -D third_party
[ -f third_party/kokoro-int8-multi-lang-v1_0.tar.bz2 ] || gh release download tts-models -R k2-fsa/sherpa-onnx -p "kokoro-int8-multi-lang-v1_0.tar.bz2" -D third_party
[ -d third_party/kokoro-int8-multi-lang-v1_0 ] || tar xjf third_party/kokoro-int8-multi-lang-v1_0.tar.bz2 -C third_party
cp third_party/sherpa-onnx-1.13.2.aar app/libs/
cd third_party/kokoro-int8-multi-lang-v1_0
cp model.int8.onnx voices.bin tokens.txt lexicon-us-en.txt lexicon-zh.txt ../../app/src/main/assets/models/
cp -r espeak-ng-data dict ../../app/src/main/assets/models/
echo "staged: app/libs/sherpa-onnx-1.13.2.aar + app/src/main/assets/models/ ($(du -sh ../../app/src/main/assets/models | cut -f1))"
