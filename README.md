# 유튜브 영상 분석 스킬 (클라우드 / 모바일용)

유튜브 링크만 주면 장면 단위로 분석하고, 그 구조를 내 영상의 대본·스토리보드·Higgsfield 프롬프트로 바꿔 주는 Claude 스킬입니다.

```
skills/youtube-analyzer/
├── SKILL.md                         # 스킬 본체 (작업 순서)
└── references/
    ├── report-template.md           # 분석 리포트 양식
    └── production-handoff.md        # 내 영상 제작 핸드오프 양식
dist/youtube-analyzer.zip            # claude.ai 업로드용 패키지
```

## 동작 방식

| 단계 | 사용하는 것 |
|---|---|
| 영상 장면 분석 | Higgsfield 커넥터 `video_analysis_create`. 유튜브 URL을 직접 받음 |
| 메타데이터·댓글 보강 | 웹 검색/가져오기 (가능한 경우에만) |
| 대체 경로 | 유튜브 "스크립트 표시" 내용을 복사해 붙여넣기 |
| 내 영상 제작 | Higgsfield 이미지·영상·음성 생성, ElevenLabs, Canva |
| 완성본 검증 | Higgsfield `virality_predictor` |
| 결과 보관 | Notion / Google Drive |

## 클라우드에 설치해 모바일에서 쓰기

스킬과 커넥터는 PC가 아니라 **claude.ai 계정**에 저장됩니다. 한 번 등록해 두면 웹, 데스크톱 앱, 모바일 앱 어디서나 그대로 쓸 수 있습니다.

1. 이 저장소에서 `dist/youtube-analyzer.zip` 을 내려받습니다.
2. claude.ai → **Settings → Capabilities**(또는 Skills)에서 코드 실행이 켜져 있는지 확인합니다.
3. **Skills → Upload skill**에서 zip 파일을 올립니다.
4. **Settings → Connectors**에서 **Higgsfield**가 연결되어 있는지 확인합니다. Notion, Google Drive, ElevenLabs, Canva는 선택입니다.
5. 모바일 앱에서 이렇게 입력해 봅니다:
   > https://youtu.be/XXXX 이 영상 분석해서 내 쇼츠에 쓸 수 있게 정리해줘

메뉴 이름은 claude.ai 업데이트에 따라 조금 다를 수 있습니다.

## 스킬 수정 후 다시 패키징

```bash
./build.sh   # dist/youtube-analyzer.zip 재생성
```
만든 zip을 claude.ai에 다시 업로드하면 반영됩니다. 이 GitHub 저장소가 스킬의 원본(백업과 버전 관리)입니다.

## 참고

- Higgsfield 장면 분석은 보통 3~5분 걸립니다. 영상이 길수록 정확도가 떨어지므로 쇼츠나 10분 이하 영상이 가장 잘 분석됩니다.
- 이미지·영상 생성은 Higgsfield 크레딧을 씁니다. 스킬은 프롬프트까지만 만들고, 실제 생성은 요청할 때만 실행합니다.
