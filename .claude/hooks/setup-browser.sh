#!/bin/bash
# 클라우드 세션에서 브라우저 MCP(.mcp.json의 "browser")가 에이전트 프록시 인증서를 신뢰하도록 설정
set -e
[ "$CLAUDE_CODE_REMOTE" = "true" ] || exit 0
CA=/root/.ccr/agent-proxy-ca.crt
[ -f "$CA" ] || exit 0
if ! command -v certutil >/dev/null; then
  apt-get install -y libnss3-tools >/dev/null 2>&1 || { apt-get update >/dev/null 2>&1 && apt-get install -y libnss3-tools >/dev/null 2>&1; }
fi
DB="$HOME/.pki/nssdb"
mkdir -p "$DB"
[ -f "$DB/cert9.db" ] || certutil -d "sql:$DB" -N --empty-password
certutil -d "sql:$DB" -L -n ccr-agent-proxy >/dev/null 2>&1 || certutil -d "sql:$DB" -A -t "C,," -n ccr-agent-proxy -i "$CA"
