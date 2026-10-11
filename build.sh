#!/usr/bin/env bash
# claude.ai 업로드용 스킬 zip 생성 (skills/ 아래 모든 스킬)
#  - dist/<스킬>.zip          : 키 없는 공개용 (GitHub에 커밋됨)
#  - dist-private/<스킬>.zip  : config/gemini_api_key.txt 가 있으면 키 포함 개인용 (커밋 안 됨)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist

for dir in skills/*/; do
  name=$(basename "$dir")
  rm -f "dist/$name.zip"
  (cd skills && zip -rq "../dist/$name.zip" "$name" \
    -x '*.DS_Store' '*/__pycache__/*' "$name/config/gemini_api_key.txt")
  echo "built dist/$name.zip"

  if [ -f "skills/$name/config/gemini_api_key.txt" ]; then
    mkdir -p dist-private
    rm -f "dist-private/$name.zip"
    (cd skills && zip -rq "../dist-private/$name.zip" "$name" -x '*.DS_Store' '*/__pycache__/*')
    echo "built dist-private/$name.zip (API 키 포함, 공유 금지)"
  fi
done
