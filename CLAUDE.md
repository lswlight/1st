# CLAUDE.md

유튜브 영상 분석 스킬(`youtube-analyzer`)의 원본 저장소. 전체 구조와 사용법은 README.md 참고.
국내 주식·코인 트레이딩 분석 스킬(`kr-trader/`)도 함께 있다. 사용법은 kr-trader/README.md 참고.

## 구조

- `skills/youtube-analyzer/` — 스킬 원본. `.claude/skills/youtube-analyzer` 는 이 폴더를 가리키는 심볼릭 링크이므로 원본만 수정한다.
- `connector/worker.js` — Cloudflare Workers MCP 커넥터 (단일 파일, 외부 의존성 없음).
- `dist/youtube-analyzer.zip` — claude.ai 업로드용 패키지. 직접 수정하지 말고 `./build.sh` 로 다시 만든다.
- `kr-trader/` — 국내 주식·코인 분석 스킬 4개 (`kr-scanner`, `kr-plan`, `kr-timemachine`, `kr-news`). `.claude/skills/kr-*` 는 이 폴더를 가리키는 링크다. 공통 코드는 `kr-trader/lib/krt.py`.

## 작업 규칙

- 스킬 파일을 바꾸면 `./build.sh` 를 실행해 `dist/youtube-analyzer.zip` 도 함께 커밋한다.
- `scripts/gemini_analyze.py` 는 파이썬 표준 라이브러리만 쓴다. 외부 패키지를 추가하지 않는다.
- Gemini API 키는 커밋하지 않는다. `config/gemini_api_key.txt` 와 `dist-private/` 는 `.gitignore` 대상이며, 클라우드 세션에서는 환경 변수 `GEMINI_API_KEY` 를 쓴다.
- Higgsfield 등 크레딧이 드는 도구는 사용자가 요청할 때만 실행한다.
- 문서와 스킬 결과물은 한국어로 작성한다.
- `kr-trader/` 의 파이썬도 표준 라이브러리만 쓴다. 바꾸면 `python3 -m unittest discover -s kr-trader/tests` 를 통과시킨다.
- `kr-trader` 는 분석 전용이다. 주문 기능을 넣지 않는다. 작업 폴더 `krt/`, `trader/` 는 커밋하지 않는다.
