#!/usr/bin/env bash
# claude.ai 업로드용 스킬 zip 생성
#  - dist/youtube-analyzer.zip          : 키 없는 공개용 (GitHub에 커밋됨)
#  - dist-private/youtube-analyzer.zip  : config/gemini_api_key.txt 가 있으면 키 포함 개인용 (커밋 안 됨)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
rm -f dist/youtube-analyzer.zip
(cd skills && zip -rq ../dist/youtube-analyzer.zip youtube-analyzer \
  -x '*.DS_Store' '*/__pycache__/*' 'youtube-analyzer/config/gemini_api_key.txt')
echo "built dist/youtube-analyzer.zip"

if [ -f skills/youtube-analyzer/config/gemini_api_key.txt ]; then
  mkdir -p dist-private
  rm -f dist-private/youtube-analyzer.zip
  (cd skills && zip -rq ../dist-private/youtube-analyzer.zip youtube-analyzer -x '*.DS_Store' '*/__pycache__/*')
  echo "built dist-private/youtube-analyzer.zip (API 키 포함, 공유 금지)"
fi
