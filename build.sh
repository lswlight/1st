#!/usr/bin/env bash
# claude.ai 업로드용 스킬 zip 생성
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
rm -f dist/youtube-analyzer.zip
(cd skills && zip -rq ../dist/youtube-analyzer.zip youtube-analyzer -x '*.DS_Store')
echo "built dist/youtube-analyzer.zip"
