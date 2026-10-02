# 유튜브 분석 커넥터 배포 안내 (Cloudflare Workers, 무료)

`worker.js` 파일 하나로 된 MCP 서버입니다. claude.ai에 커넥터로 등록하면 채팅(모바일 포함)에서
`analyze_youtube_video` 도구로 Gemini 영상 분석을 바로 호출합니다. Gemini 키는 이 서버에만 보관됩니다.

PC 브라우저에서 진행하는 것을 권합니다. 10~20분 걸립니다.

## 1. Cloudflare 가입
https://dash.cloudflare.com/sign-up 에서 무료 계정을 만듭니다. 결제 정보는 필요 없습니다.

## 2. Worker 만들기
1. 대시보드 왼쪽 메뉴에서 **Compute (Workers) → Workers & Pages** 로 갑니다.
2. **Create → Worker → "Hello World" 시작** 을 고릅니다.
3. 이름을 `youtube-analyzer` 로 정하고 **Deploy** 를 누릅니다.
4. **Edit code** 를 누릅니다. 편집기에 있는 기본 코드를 모두 지우고,
   이 저장소의 `connector/worker.js` 내용을 통째로 붙여넣은 뒤 **Deploy** 를 누릅니다.

## 3. 비밀값 2개 등록
Worker 화면 → **Settings → Variables and Secrets → Add** 에서 **Type: Secret** 으로 두 개를 추가합니다.

| 이름 | 값 |
|---|---|
| `GEMINI_API_KEY` | Google AI Studio 에서 받은 Gemini 키 |
| `ACCESS_TOKEN` | 남이 못 맞힐 긴 영문+숫자 문자열 (예: 비밀번호 생성기로 32자) |

`ACCESS_TOKEN` 은 커넥터 주소에 들어가는 비밀번호 역할입니다. 이게 없으면 아무나 내 Gemini 한도를 쓸 수 있습니다.
저장 후 다시 **Deploy** 합니다.

## 4. 커넥터 주소 확인
Worker 화면 위쪽에 `https://youtube-analyzer.<내계정>.workers.dev` 같은 주소가 있습니다. 커넥터 주소는:

```
https://youtube-analyzer.<내계정>.workers.dev/mcp/<ACCESS_TOKEN 값>
```

## 5. claude.ai 에 커넥터 등록
1. claude.ai → **Settings → Connectors → Add custom connector**
2. 이름: `YouTube Analyzer`, URL: 4번 주소. 인증(OAuth) 항목은 비워 둡니다.
3. 추가 후 새 채팅에서 도구 목록에 `analyze_youtube_video` 가 보이면 성공입니다.

커넥터는 계정에 저장되므로 휴대폰 앱에서도 그대로 쓸 수 있습니다.

## 6. 스킬 다시 올리기
키가 없는 공개용 스킬 zip(`dist/youtube-analyzer.zip`)을 Settings → Skills 에 올립니다.
이전에 올린 키 포함 zip 은 지웁니다. 스킬은 커넥터가 있으면 커넥터를 먼저 씁니다.

## 문제 해결
- 커넥터 추가 시 오류: 주소 끝의 `/mcp/<토큰>` 이 정확한지, 비밀값 저장 후 Deploy 했는지 확인.
- 분석 결과에 `ERROR 429`: Gemini 무료 한도. 1분 뒤 다시 하거나 구간(start/end)을 줄입니다.
- `ERROR 503`: Gemini 서버 혼잡. 잠시 뒤 다시 시도합니다.
- 키를 바꾸려면 Cloudflare 의 `GEMINI_API_KEY` 값만 바꾸면 됩니다. 스킬이나 커넥터는 그대로 둡니다.

## (선택) 명령줄로 배포
Node.js 가 있는 PC라면:
```bash
cd connector
npx wrangler login
npx wrangler deploy
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put ACCESS_TOKEN
```
