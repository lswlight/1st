# 유튜브 영상 분석 스킬 (클라우드 / 모바일용)

유튜브 링크만 주면 장면 단위로 분석하고, 그 구조를 내 영상의 대본·스토리보드·Higgsfield 프롬프트로 바꿔 주는 Claude 스킬입니다.

```
skills/youtube-analyzer/
├── SKILL.md                         # 스킬 본체 (작업 순서)
├── scripts/gemini_analyze.py        # Gemini API 영상 분석 (표준 라이브러리만 사용)
├── config/                          # gemini_api_key.txt 위치 (git 제외)
└── references/
    ├── report-template.md           # 분석 리포트 양식
    └── production-handoff.md        # 내 영상 제작 핸드오프 양식
dist/youtube-analyzer.zip            # claude.ai 업로드용 패키지
```

## 동작 방식 (무료 경로가 기본)

| 순서 | 경로 | 비용 | 하는 일 |
|---|---|---|---|
| A | **Gemini API** (`scripts/gemini_analyze.py`) | 무료 한도 안에서 0원 | Gemini가 유튜브 영상을 직접 보고 장면표, 첫 15초 정밀 기록, 대본 전문을 뽑음 |
| B | Gemini 앱 결과 붙여넣기 | 무료 | 코드 실행이 안 될 때 |
| C | 유튜브 "스크립트 표시" 붙여넣기 | 무료 | 대본만으로 분석 |
| D | Higgsfield `video_analysis_create` | 크레딧 소모 | 직접 요청할 때만 사용 |

Gemini가 **영상을 보고**, Claude가 그 결과로 **벤치마킹 리포트, 대본, 스토리보드, Higgsfield 프롬프트**를 만듭니다.
Higgsfield 이미지·영상 생성과 `virality_predictor`도 요청할 때만 실행합니다.

## Gemini API 키 준비 (1회)

1. https://aistudio.google.com 에 Google 계정으로 로그인 → **Get API key → Create API key**. 결제 정보를 넣지 않으면 무료 등급으로만 동작해서 요금이 청구되지 않습니다.
2. 키를 넣는 방법 중 하나를 고릅니다.
   - **claude.ai (모바일 포함)**: `skills/youtube-analyzer/config/gemini_api_key.txt` 에 키를 저장하고 `./build.sh` 를 실행하면 `dist-private/youtube-analyzer.zip` (키 포함)이 생깁니다. 이 zip을 업로드합니다. 이 파일은 GitHub에 올라가지 않으니 남에게 공유하지 마세요.
   - **Claude Code (웹/터미널)**: 환경 변수 `GEMINI_API_KEY` 로 등록합니다.
3. claude.ai의 코드 실행 네트워크 설정에서 외부 접속이 막혀 있으면 `generativelanguage.googleapis.com` 을 허용 도메인에 추가합니다.

## 클라우드에 설치해 모바일에서 쓰기

스킬과 커넥터는 PC가 아니라 **claude.ai 계정**에 저장됩니다. 한 번 등록해 두면 웹, 데스크톱 앱, 모바일 앱 어디서나 그대로 쓸 수 있습니다.

1. 이 저장소에서 `dist/youtube-analyzer.zip` 을 내려받습니다.
2. claude.ai → **Settings → Capabilities**(또는 Skills)에서 코드 실행이 켜져 있는지 확인합니다.
3. **Skills → Upload skill**에서 zip 파일을 올립니다. 키를 넣었다면 `dist-private/` 쪽 zip을 올립니다.
4. Higgsfield, Notion, Google Drive, ElevenLabs, Canva 커넥터는 제작 단계용 선택 사항입니다.
5. 모바일 앱에서 이렇게 입력해 봅니다:
   > https://youtu.be/XXXX 이 영상 분석해서 내 쇼츠에 쓸 수 있게 정리해줘

메뉴 이름은 claude.ai 업데이트에 따라 조금 다를 수 있습니다.

## 스킬 수정 후 다시 패키징

```bash
./build.sh   # dist/youtube-analyzer.zip 재생성
```
만든 zip을 claude.ai에 다시 업로드하면 반영됩니다. 이 GitHub 저장소가 스킬의 원본(백업과 버전 관리)입니다.

## 참고

- Gemini 무료 등급은 하루 요청 수와 분당 토큰에 한도가 있습니다. 긴 영상은 `--low-res` 나 `--start/--end` 로 나눠 분석합니다.
- 공개 또는 일부 공개 영상만 Gemini API로 분석할 수 있습니다.
- 이미지·영상 생성은 Higgsfield 크레딧을 씁니다. 스킬은 프롬프트까지만 만들고, 실제 생성은 요청할 때만 실행합니다.
