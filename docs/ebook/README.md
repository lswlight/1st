# 전자책: 코딩 몰라도 앱 만들어 플레이스토어에 올리기

직장인이 바이브 코딩(AI에게 말로 시켜 앱 만들기)으로 '자동가계부' 앱을 만들어 구글 플레이 심사까지 제출한 과정을 담은 전자책 원본이다. 내용 자료는 `handoff.md`.

## 구조

- `book.json` — 책 제목·표지 문구와 장 목록(제목, 파일, 출력 파일 이름)
- `chapters/` — 장별 본문 HTML 조각(`ch01.html` ~ `ch11.html`), 머리말 `front.html`
- `style.css` — 판형(182×257mm)과 디자인. 글꼴은 `fonts/`의 Pretendard(OFL)
- `build.py` — 장별 PDF와 표지·목차가 있는 통합본을 만든다
- `render.cjs` — HTML 한 개를 Playwright(크롬)로 PDF 출력
- `shots.json` — 캡처별 흐림(개인정보) 영역 좌표
- `images/` — 저장소에 올려도 되는 그림

## 그림 넣기

본문에서 `<fig src="파일이름" ...></fig>` 로 그림을 넣는다. 파일은 `private/`, `images/`, `expense-app/docs/`(+`screens/`, `store/`)에서 찾는다.

| 속성 | 뜻 |
|---|---|
| `w` | 너비(예: `80%`) |
| `cap` | 캡션(HTML 가능) |
| `crop` | `x,y,w,h` 원본 픽셀 기준으로 잘라 쓰기 |
| `marks` | `x,y,w,h,번호[,r]` 빨간 상자와 번호. `;`로 여러 개. 좌표는 잘라 낸 그림 기준. `r`은 번호를 오른쪽에 |
| `blur` | `x,y,w,h` 원본 기준 흐림(픽셀 자체를 뭉갬). `shots.json`에도 지정 가능 |

## 계정 화면 캡처(비공개)

Play Console·Cloudflare·Search Console·GitHub 화면 캡처 39장(`ebook-screenshots.zip`)은 **저장소에 커밋하지 않는다.**
`docs/ebook/private/` 에 풀어 두면 빌드 때 자동으로 쓰인다(`.gitignore` 대상). 없으면 그 자리에 '캡처 자리' 상자가 나온다.

## 만들기

```bash
cd docs/ebook
python3 build.py          # 모든 장 + 통합본 → out/
python3 build.py 3 4      # 3, 4장만
python3 build.py --book   # 통합본만
```

필요: Python(Pillow, pypdf), Node + Playwright(크롬). 결과물은 `out/`(커밋하지 않음).
목차 쪽 번호는 1차 출력의 PDF 책갈피에서 찾아 2차 출력에 넣는다.
