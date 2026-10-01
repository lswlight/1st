# 내 영상 제작 핸드오프

분석한 레퍼런스의 **구조는 빌리고 내용은 내 주제로** 바꾼다. 문장·장면을 그대로 베끼지 않는다.

## 1. 기획
- 내 주제 / 타깃 시청자 / 형식(쇼츠 9:16, 롱폼 16:9) / 목표 길이
- 차용한 레퍼런스 구조: 예) "결과선공개 훅 → 3단 리스트 → 반전 → CTA"

## 2. 제목 & 썸네일
- 제목 후보 5개 (레퍼런스의 제목 장치를 표시)
- 썸네일 문구 3개 + 구도 설명

## 3. 대본 초안
```
[0:00–0:03] 훅: …
[0:03–0:15] 문제제기: …
…
[끝] CTA: …
```

## 4. 스토리보드
| 컷 | 길이 | 화면 | 대사/자막 | 생성 방식 |
|---|---|---|---|---|
| 1 | 3s | | | 실촬영 / Higgsfield 이미지 / Higgsfield 영상 / 스톡 |

## 5. Higgsfield 프롬프트
AI 생성 컷마다 작성한다. 모델 선택이 애매하면 실행 시 `models_explore(action:'recommend')` 로 추천받는다.

```
컷 1 — (한국어 설명)
type: video | image
aspect: 9:16
prompt (EN): cinematic close-up of …, soft rim light, shallow depth of field, slow push-in, 4s
```

여러 컷을 한 번에 만들 때는 `generate_video_batch` / `generate_image_batch` 후 `jobs_wait`, 다단계 영상이면 먼저 `get_workflow_instructions` 를 호출한다.
**생성은 크레딧이 들기 때문에 사용자가 "만들어줘"라고 할 때만 실행한다.**

## 6. 음성 / 기타 연결
- 내레이션: Higgsfield `generate_audio` 또는 ElevenLabs 커넥터(`creative_generate_speech`)
- 썸네일/디자인: Canva 커넥터
- 기획서 보관: Notion / Google Drive
