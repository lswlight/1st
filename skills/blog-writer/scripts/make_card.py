#!/usr/bin/env python3
"""블로그 본문용 이미지(정보 카드, 관계도, 단계 카드)를 PNG로 만든다. 파이썬 표준 라이브러리만 사용.

JSON 사양 파일 하나로 카드 한 장을 만든다. HTML을 만든 뒤 헤드리스 Chromium으로 PNG를 찍는다.
Chromium이 없으면 HTML만 남기고 알려 준다 (브라우저로 열어 캡처하면 된다).

사용 예:
  python3 make_card.py spec.json -o out.png

사양 예시 (type 은 info / relation / steps 중 하나):
  {"type": "info", "title": "닥터X 기본 정보", "subtitle": "SBS 금토드라마",
   "rows": [["방송", "금·토 밤 9시 50분"], ["분량", "12부작"]], "footer": "내 블로그 이름"}

  {"type": "relation", "title": "닥터X 등장인물 관계도",
   "center": {"name": "계수정", "sub": "김지원 · 천재 외과의"},
   "nodes": [{"name": "장희숙", "sub": "이정은 · 소개소 소장", "rel": "병원에 파견"}]}

  {"type": "steps", "title": "따라잡는 3단계", "steps": ["1~2회 몰아 보기", "요약 영상 복습"]}

선택 항목: "accent" (강조 색, 기본 #2563eb), "footer" (아래쪽 작은 글씨, 블로그 이름 등)
"""
import argparse
import glob
import html
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

W = 1080
FONT = "'Noto Sans KR', 'Apple SD Gothic Neo', 'Malgun Gothic', 'NanumGothic', 'WenQuanYi Zen Hei', sans-serif"
FONT_LINK = '<link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;700;900&display=swap" rel="stylesheet">'


def e(s):
    return html.escape(str(s))


def page(body, height, accent, footer):
    foot = f'<div class="foot">{e(footer)}</div>' if footer else ""
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">{FONT_LINK}
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
html{{background:#f8fafc}}
body{{width:{W}px;height:{height}px;background:#f8fafc;font-family:{FONT};color:#0f172a;overflow:hidden}}
.wrap{{position:relative;width:{W}px;height:{height}px;padding:72px 72px 0}}
.bar{{width:88px;height:10px;border-radius:5px;background:{accent};margin-bottom:28px}}
h1{{font-size:60px;font-weight:900;line-height:1.25;letter-spacing:-1px}}
.sub{{font-size:32px;color:#475569;margin-top:14px}}
.foot{{position:absolute;z-index:5;left:72px;bottom:44px;font-size:24px;color:#94a3b8}}
.row{{display:flex;align-items:center;background:#fff;border-radius:22px;padding:30px 36px;margin-top:20px;box-shadow:0 2px 0 #e2e8f0}}
.k{{width:220px;flex:none;font-size:30px;font-weight:700;color:{accent}}}
.v{{font-size:34px;font-weight:700;line-height:1.35}}
.num{{width:72px;height:72px;flex:none;border-radius:50%;background:{accent};color:#fff;font-size:36px;font-weight:900;display:flex;align-items:center;justify-content:center;margin-right:30px}}
.node{{position:absolute;width:300px;padding:26px 20px;background:#fff;border-radius:24px;text-align:center;box-shadow:0 2px 0 #e2e8f0;border:3px solid #e2e8f0}}
.node.c{{width:340px;background:{accent};border-color:{accent};color:#fff}}
.node b{{display:block;font-size:36px;font-weight:900;white-space:nowrap}}
.node span{{display:block;font-size:24px;margin-top:8px;color:#64748b;line-height:1.35}}
.node.c span{{color:#e0e7ff}}
.rel{{position:absolute;transform:translate(-50%,-50%);background:#fff;border:2px solid {accent};color:{accent};font-size:24px;font-weight:700;padding:6px 16px;border-radius:999px;white-space:nowrap}}
</style></head><body><div class="wrap">{body}{foot}</div></body></html>"""


def header(spec):
    sub = f'<div class="sub">{e(spec["subtitle"])}</div>' if spec.get("subtitle") else ""
    return f'<div class="bar"></div><h1>{e(spec["title"])}</h1>{sub}'


def info(spec, accent):
    rows = "".join(f'<div class="row"><div class="k">{e(k)}</div><div class="v">{e(v)}</div></div>'
                   for k, v in spec["rows"])
    h = 280 + (50 if spec.get("subtitle") else 0) + 126 * len(spec["rows"]) + 95
    return page(header(spec) + '<div style="margin-top:36px">' + rows + "</div>", h, accent, spec.get("footer"))


def steps(spec, accent):
    rows = "".join(f'<div class="row"><div class="num">{i}</div><div class="v">{e(s)}</div></div>'
                   for i, s in enumerate(spec["steps"], 1))
    h = 280 + (50 if spec.get("subtitle") else 0) + 152 * len(spec["steps"]) + 95
    return page(header(spec) + '<div style="margin-top:36px">' + rows + "</div>", h, accent, spec.get("footer"))


def relation(spec, accent):
    nodes = spec["nodes"]
    if not 1 <= len(nodes) <= 6:
        sys.exit("relation 카드는 주변 인물 1~6명까지 지원해요.")
    h = 1180
    top = 300 if spec.get("subtitle") else 250
    bottom = h - 110
    cx, cy = W / 2, (top + bottom) / 2
    rx, ry = (W - 144 - 300) / 2, (bottom - top) / 2 - 85
    c = spec["center"]
    parts = [header(spec)]
    lines, labels = [], []
    for i, n in enumerate(nodes):
        ang = -math.pi / 2 + 2 * math.pi * i / len(nodes)
        if len(nodes) in (2, 4):
            ang += math.pi / len(nodes)  # 2명은 좌우, 4명은 네 귀퉁이
        # 원을 사각형 쪽으로 펴서 화면 끝까지 쓴다
        dx = max(-1.0, min(1.0, math.cos(ang) * 1.42))
        dy = max(-1.0, min(1.0, math.sin(ang) * 1.42))
        x, y = cx + rx * dx, cy + ry * dy
        lines.append(f'<line x1="{cx}" y1="{cy}" x2="{x:.0f}" y2="{y:.0f}" stroke="{accent}" stroke-width="4" stroke-dasharray="10 10"/>')
        parts.append(f'<div class="node" style="left:{x - 150:.0f}px;top:{y - 75:.0f}px"><b>{e(n["name"])}</b><span>{e(n.get("sub", ""))}</span></div>')
        if n.get("rel"):
            labels.append(f'<div class="rel" style="left:{(cx + x) / 2:.0f}px;top:{(cy + y) / 2:.0f}px">{e(n["rel"])}</div>')
    svg = f'<svg width="{W}" height="{h}" style="position:absolute;left:0;top:0">{"".join(lines)}</svg>'
    center = f'<div class="node c" style="left:{cx - 170:.0f}px;top:{cy - 80:.0f}px"><b>{e(c["name"])}</b><span>{e(c.get("sub", ""))}</span></div>'
    body = svg + "".join(parts) + "".join(labels) + center
    return page(body, h, accent, spec.get("footer"))


def find_chrome():
    """(실행 파일, headless_shell 여부). headless_shell 은 창 크기 = 화면 크기라 그대로 찍힌다."""
    shells = sorted(glob.glob("/opt/pw-browsers/chromium_headless_shell*/*/headless_shell")
                    + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium_headless_shell*/*/headless_shell")))
    if shells:
        return shells[-1], True
    for p in [os.environ.get("CHROME_PATH"), "/opt/pw-browsers/chromium"]:
        if p and os.path.exists(p):
            return p, False
    for name in ["chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "chrome"]:
        p = shutil.which(name)
        if p:
            return p, False
    hits = sorted(glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium*/chrome-linux*/chrome")))
    return (hits[-1], False) if hits else (None, False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec", help="카드 사양 JSON 파일")
    ap.add_argument("-o", "--out", required=True, help="저장할 PNG 경로")
    a = ap.parse_args()

    spec = json.load(open(a.spec, encoding="utf-8"))
    accent = spec.get("accent", "#2563eb")
    builders = {"info": info, "relation": relation, "steps": steps}
    if spec.get("type") not in builders:
        sys.exit("type 은 info / relation / steps 중 하나여야 해요.")
    doc = builders[spec["type"]](spec, accent)
    height = int(doc.split("height:", 1)[1].split("px", 1)[0])

    out = os.path.abspath(a.out)
    html_path = os.path.splitext(out)[0] + ".html"
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(doc)

    chrome, is_shell = find_chrome()
    if not chrome:
        print(f"Chromium을 찾지 못해 HTML만 만들었어요: {html_path}\n브라우저로 열어 캡처해 쓰세요.")
        sys.exit(2)
    with tempfile.TemporaryDirectory() as prof:
        # 일반 Chrome 의 headless 모드는 창 높이에서 약 87px 를 빼고 그리므로 그만큼 늘린다 (아래 여백은 배경색)
        win_h = height if is_shell else height + 87
        flags = [] if is_shell else ["--headless"]
        subprocess.run([chrome, *flags, "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
                        f"--user-data-dir={prof}", "--virtual-time-budget=3000",
                        f"--window-size={W},{win_h}", f"--screenshot={out}", "file://" + html_path],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
    os.remove(html_path)
    print(f"만들었어요: {out}")


if __name__ == "__main__":
    main()
