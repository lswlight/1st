# 자동가계부 가족 공유 서버 (Cloudflare Workers + D1, 무료)

앱의 **[가족 공유 시작하기]** 가 쓰는 서버입니다. 앱 사용자는 아무 설정 없이 버튼만 누르면 되고,
이 서버는 **앱을 배포하는 사람이 처음 한 번만** 올려 두면 됩니다. `worker.js` 파일 하나이며 외부 라이브러리가 없습니다.

- 가족 방마다 무작위 **쓰기 키**(앱만 가짐, 서버에는 해시로만 저장)와 **보기 토큰**(보기 링크에 들어감)을 만듭니다.
- 보기 링크 `https://<서버>/v/<보기 토큰>` 을 열면 앱 없이 웹에서 가족 가계부를 봅니다 (아이폰·PC).
- [가족 공유 끄기] 를 누르면 그 가족 방과 내역이 서버에서 모두 지워집니다.
- 저장하는 정보: 이름(예: 남편), 사용처, 금액, 카드, 결제 시각. 원본 알림 문구·전화번호·계좌번호는 받지 않습니다.

## 대시보드로 올리기 (PC, 10분)

1. https://dash.cloudflare.com 로그인 (유튜브 커넥터 때 쓴 계정 그대로)
2. **Storage & Databases → D1 SQL Database → Create** → 이름 `moneybook` → Create
3. **Compute (Workers) → Workers & Pages → Create → Worker → "Hello World"** → 이름 `moneybook-sync` → **Deploy**
4. **Edit code** → 기본 코드를 모두 지우고 이 폴더의 `worker.js` 를 통째로 붙여 넣기 → **Deploy**
5. Worker 화면 → **Settings → Bindings → Add → D1 database** → Variable name **`DB`**, 데이터베이스 `moneybook` 선택 → **Deploy**
6. Worker 주소 확인: `https://moneybook-sync.<내계정>.workers.dev` — 브라우저로 열어
   "자동가계부 가족 공유 서버가 동작 중입니다." 가 보이면 성공입니다. 표는 첫 요청 때 자동으로 만들어집니다.
7. 이 주소를 앱에 넣습니다: 앱 코드 `FamilySync.DEFAULT_SERVER` 에 넣고 다시 빌드하거나,
   당장은 앱 [가족] → [공유 설정] → '공유 서버 주소' 에 붙여 넣습니다.

## 명령줄로 올리기 (선택)

```bash
cd sync-server
npx wrangler login
npx wrangler d1 create moneybook        # 나온 database_id 를 wrangler.toml 에 넣는다
npx wrangler deploy
```

## 회사 도메인 연결 (graftkorea.com)

Worker → 설정(Settings) → 도메인 및 경로(Domains & Routes) → 추가(Add) → 사용자 지정 도메인(Custom domain) → `graftkorea.com`.
연결하면 같은 서버가 `https://graftkorea.com` 에서 회사 첫 화면(`/`), 앱 소개(`/app`), 개인정보처리방침(`/privacy`), 가족 공유 API(`/api/…`, `/v/…`)를 함께 제공한다. 서버 동작 확인은 `/health`.

## 공개 페이지

- `/app` — 앱 소개·다운로드 페이지. 카카오톡으로 앱을 알릴 때 이 주소를 보낸다.
- `/privacy` — 개인정보처리방침. 플레이스토어 등록의 '개인정보처리방침 URL' 에 쓴다. `/privacy#delete` 는 데이터 삭제 안내(플레이스토어 '데이터 삭제 URL').
- 내용을 바꾸면 `worker.js` 를 대시보드 코드 편집 화면에 다시 붙여넣고 배포한다. 앱 버전이 바뀌면 `APP_VERSION` 도 함께 고친다.

## 무료 한도

Workers 무료: 하루 요청 10만 건. D1 무료: 저장 5GB, 하루 읽기 500만·쓰기 10만 행.
결제 1건 = 쓰기 1회라 가족 수천 곳이 써도 무료 범위 안입니다.
