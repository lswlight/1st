#!/usr/bin/env python3
"""Gemini API로 유튜브 영상을 직접 보고 장면별 분석 원자료를 뽑는다.

표준 라이브러리만 사용한다 (pip 설치 불필요).

사용법:
    python3 gemini_analyze.py <youtube_url> [--start 0s] [--end 60s] [--low-res] [--out FILE]

API 키는 다음 순서로 찾는다:
    1. 환경변수 GEMINI_API_KEY
    2. 이 스크립트 옆의 ../config/gemini_api_key.txt
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models"
DEFAULT_MODEL = os.environ.get("GEMINI_MODEL", "gemini-flash-latest")
# 기본 모델이 과부하(503)거나 사라졌을(404) 때 차례로 시도할 대체 모델
FALLBACK_MODELS = ["gemini-3.8-flash", "gemini-3.5-flash", "gemini-flash-lite-latest"]
KEY_FILE = Path(__file__).resolve().parent.parent / "config" / "gemini_api_key.txt"

PROMPT = """너는 유튜브 영상 편집자이자 콘텐츠 분석가다. 이 영상을 처음부터 끝까지 직접 보고 듣고,
다른 사람이 영상을 보지 않고도 똑같이 재현할 수 있을 만큼 구체적으로 기록하라.
한국어로 작성하고, 보이지 않거나 들리지 않는 것은 지어내지 말고 "확인 불가"라고 써라.

## 1. 기본 정보
- 추정 제목/주제, 길이, 화면비(가로/세로), 형식(쇼츠/롱폼), 출연자 수와 특징, 언어

## 2. 첫 15초 정밀 기록 (1초 단위)
| 시간 | 화면 | 음성(원문 그대로) | 화면 텍스트/자막 | 소리(BGM/효과음) |

## 3. 장면별 분석 (컷이 바뀔 때마다 한 행)
| 시작–끝 | 화면(구도, 피사체, 배경, 색감) | 카메라(샷 크기, 움직임) | 음성(원문 요약, 핵심 문장은 원문) | 화면 텍스트/자막 | 편집(전환, 줌, 효과, B롤) | 소리 |

## 4. 편집 스타일 요약
- 총 컷 수와 평균 컷 길이(초), 자막 디자인(위치/색/크기/강조 방식), 반복되는 편집 패턴, BGM 분위기 변화 지점

## 5. 대본 전문
- 들리는 말을 시간 표시와 함께 원문 그대로 받아적어라. 너무 길면 핵심 구간 위주로 쓰고 생략 구간을 표시하라.

## 6. 구성 구간
- 훅 / 문제제기 / 전개 / 반전·클라이맥스 / 결론 / CTA 로 나누고 각 구간의 시간 범위를 적어라.
"""


class ModelError(Exception):
    def __init__(self, code, msg):
        super().__init__(f"ERROR {code}: {msg}")
        self.code = code


def load_key():
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key and KEY_FILE.exists():
        key = KEY_FILE.read_text().strip()
    if not key:
        sys.exit("ERROR: GEMINI_API_KEY 환경변수 또는 config/gemini_api_key.txt 가 필요합니다.")
    return key


def normalize_url(url):
    # shorts / youtu.be 링크를 watch?v= 형태로 통일
    m = re.search(r"(?:shorts/|youtu\.be/|v=)([A-Za-z0-9_-]{11})", url)
    return f"https://www.youtube.com/watch?v={m.group(1)}" if m else url


def analyze(url, model, start=None, end=None, low_res=False):
    video_part = {"file_data": {"file_uri": normalize_url(url)}}
    if start or end:
        meta = {}
        if start:
            meta["start_offset"] = start
        if end:
            meta["end_offset"] = end
        video_part["video_metadata"] = meta

    body = {
        "contents": [{"parts": [video_part, {"text": PROMPT}]}],
        "generationConfig": {"temperature": 0.2},
    }
    if low_res:
        # 긴 영상에서 토큰(=무료 한도) 절약
        body["generationConfig"]["mediaResolution"] = "MEDIA_RESOLUTION_LOW"

    req = urllib.request.Request(
        f"{API_ROOT}/{model}:generateContent",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": load_key()},
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.load(r)
            break
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")
            if e.code in (429, 500, 503) and attempt < 2:
                time.sleep(10 * (attempt + 1))
                continue
            raise ModelError(e.code, msg[:1000])

    try:
        text = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
    except (KeyError, IndexError):
        sys.exit("ERROR: 응답에 결과가 없습니다: " + json.dumps(data, ensure_ascii=False)[:1000])
    usage = data.get("usageMetadata", {})
    return text, usage


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--start", help="분석 시작 지점, 예: 30s")
    ap.add_argument("--end", help="분석 끝 지점, 예: 120s")
    ap.add_argument("--low-res", action="store_true", help="긴 영상용 저해상도 모드")
    ap.add_argument("--out", help="결과 저장 파일 (기본: 표준출력)")
    a = ap.parse_args()

    candidates = [a.model] + [m for m in FALLBACK_MODELS if m != a.model]
    for model in candidates:
        try:
            text, usage = analyze(a.url, model, a.start, a.end, a.low_res)
            break
        except ModelError as e:
            print(f"{model} 실패 ({e.code}), 다음 모델 시도", file=sys.stderr)
            if e.code not in (404, 429, 500, 503) or model == candidates[-1]:
                sys.exit(str(e))
    header = f"<!-- source: Gemini API ({model}) | url: {a.url} | tokens: {usage.get('totalTokenCount', '?')} -->\n"
    if a.out:
        Path(a.out).write_text(header + text)
        print(f"saved {a.out} ({usage.get('totalTokenCount', '?')} tokens)")
    else:
        print(header + text)


if __name__ == "__main__":
    main()
