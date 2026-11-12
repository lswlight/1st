#!/usr/bin/env python3
"""전자책 빌드: 장별 HTML 조각 → 장별 PDF + 표지·목차가 있는 통합본 PDF.

사용법
  python3 build.py            # 모든 장 + 통합본
  python3 build.py 3 4        # 3장, 4장만 (통합본은 만들지 않음)
  python3 build.py --book     # 통합본만

필요: Pillow, pypdf, Node + Playwright(크롬).
계정 화면 캡처(ebook-screenshots.zip)는 docs/ebook/private/ 에 풀어 둔다(커밋 금지, .gitignore 대상).
없는 그림은 '캡처 자리' 상자로 표시된다.
"""
import html
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageFilter
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
OUT = ROOT / "out"
IMG_OUT = OUT / "img"
SEARCH = [
    ROOT / "private",
    ROOT / "images",
    REPO / "expense-app/docs",
    REPO / "expense-app/docs/screens",
    REPO / "expense-app/docs/store",
]
BOOK = json.loads((ROOT / "book.json").read_text(encoding="utf-8"))
SHOTS = json.loads((ROOT / "shots.json").read_text(encoding="utf-8")) if (ROOT / "shots.json").exists() else {}
MAX_W = 1500


def find_image(name):
    for d in SEARCH:
        for p in [d / name, *d.rglob(name)] if d.exists() else []:
            if p.is_file():
                return p
    stem = Path(name).stem  # 확장자가 달라도 찾는다 (.jpg ↔ .png)
    for d in SEARCH:
        if d.exists():
            for p in d.rglob(stem + ".*"):
                if p.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp"):
                    return p
    return None


def prepare_image(src: Path, blur, crop=None):
    """크기를 줄이고, 개인정보 영역은 픽셀 자체를 흐리게 해서 out/img 에 저장한다.
    crop 이 있으면 (x, y, w, h) 부분만 잘라 쓴다. 표시(marks) 좌표는 잘라 낸 그림 기준."""
    IMG_OUT.mkdir(parents=True, exist_ok=True)
    im = Image.open(src)
    im = im.convert("RGB")
    W, H = im.size
    for (x, y, w, h) in blur:
        box = (int(x), int(y), int(x + w), int(y + h))
        region = im.crop(box)
        # 모자이크 + 강한 흐림 (원래 글자를 되살릴 수 없게)
        small = region.resize((max(1, region.width // 14), max(1, region.height // 14)), Image.BILINEAR)
        region = small.resize(region.size, Image.NEAREST).filter(ImageFilter.GaussianBlur(6))
        im.paste(region, box)
    suffix = ""
    if crop:
        x, y, w, h = (int(v) for v in crop)
        im = im.crop((x, y, x + w, y + h))
        W, H = im.size
        suffix = f"-{x}-{y}-{w}-{h}"
    scale = 1.0
    if W > MAX_W:
        scale = MAX_W / W
        im = im.resize((MAX_W, round(H * scale)), Image.LANCZOS)
    dst = IMG_OUT / (src.stem + suffix + ".jpg")
    im.save(dst, "JPEG", quality=88, optimize=True)
    return dst, (W, H)


def parse_boxes(s, n):
    out = []
    for part in (s or "").split(";"):
        part = part.strip()
        if not part:
            continue
        vals = [v.strip() for v in part.split(",")]
        out.append(vals[:n] + vals[n:])
    return out


FIG_RE = re.compile(r'<fig\s+((?:[^>"]|"[^"]*")*?)\s*/?>(?:</fig>)?', re.S)
ATTR_RE = re.compile(r'(\w+)="(.*?)"', re.S)


def render_fig(m):
    attrs = dict(ATTR_RE.findall(m.group(1)))
    name = attrs["src"]
    width = attrs.get("w", "100%")
    cap = attrs.get("cap", "")
    cls = attrs.get("class", "")
    conf = SHOTS.get(Path(name).stem, {})
    src = find_image(name)
    capt = f"<figcaption>{cap}</figcaption>" if cap else ""
    if src is None:
        return (f'<figure class="{cls}" style="width:{width}"><div class="placeholder"><b>캡처 자리</b>'
                f'{html.escape(name)}<br>(ebook-screenshots.zip 을 넣으면 채워집니다)</div>{capt}</figure>')
    blur = [tuple(float(v) for v in b[:4]) for b in parse_boxes(conf.get("blur", ""), 4)]
    blur += [tuple(float(v) for v in b[:4]) for b in parse_boxes(attrs.get("blur", ""), 4)]
    crop = [float(v) for v in attrs["crop"].split(",")] if attrs.get("crop") else None
    dst, (W, H) = prepare_image(src, blur, crop)
    marks_html = ""
    marks = attrs.get("marks") or conf.get("marks", "")
    for b in parse_boxes(marks, 5):
        x, y, w, h = (float(v) for v in b[:4])
        label = b[4] if len(b) > 4 else ""
        side = " r" if len(b) > 5 and b[5] == "r" else ""
        style = f"left:{x / W * 100:.2f}%;top:{y / H * 100:.2f}%;width:{w / W * 100:.2f}%;height:{h / H * 100:.2f}%"
        num = f"<i>{label}</i>" if label else ""
        marks_html += f'<span class="mark{side}" style="{style}">{num}</span>'
    rel = dst.relative_to(OUT).as_posix()
    return (f'<figure class="{cls}" style="width:{width}"><div class="shot"><img src="{rel}" alt="">{marks_html}</div>'
            f"{capt}</figure>")


def chapter_html(ch):
    body = (ROOT / "chapters" / ch["file"]).read_text(encoding="utf-8")
    body = FIG_RE.sub(render_fig, body)
    todo = "".join(f"<li>{t}</li>" for t in ch.get("todo", []))
    opener = f"""
<section class="opener" id="ch{ch['no']}">
  <div class="label">{ch['label']}</div>
  <div class="no">{ch['num']}</div>
  <h1>{ch['title']}</h1>
  <div class="lead">{ch.get('lead', '')}</div>
  {'<div class="todo"><b>이 장에서 할 일</b><ul>' + todo + '</ul></div>' if todo else ''}
</section>"""
    return opener + f'\n<section class="body">\n{body}\n<div class="chapter-end">· · ·</div>\n</section>\n'


def page(title, inner):
    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>{html.escape(title)}</title>
<link rel="stylesheet" href="../style.css"></head>
<body>
{inner}
</body></html>"""


def render(html_text, name):
    OUT.mkdir(exist_ok=True)
    hp = OUT / f"{name}.html"
    hp.write_text(html_text, encoding="utf-8")
    pdf = OUT / f"{name}.pdf"
    subprocess.run(["node", str(ROOT / "render.cjs"), str(hp), str(pdf)], check=True)
    return pdf


def cover_html():
    b = BOOK
    return f"""
<section class="cover">
  <div class="inner">
    <div class="kicker">{b['kicker']}</div>
    <h1>{b['cover_title']}</h1>
    <div class="desc">{b['cover_desc']}</div>
  </div>
  <div class="band"></div>
  <div class="brand"><img class="icon" src="{render_fig_src('icon-512.png')}" alt=""><b>{b['author']}</b><span>{b['edition']}</span></div>
  <div class="phones">
    <img src="{render_fig_src('screen2-calendar-v2.png')}" alt="">
    <img class="mid" src="{render_fig_src('screen1-main-v2.png')}" alt="">
    <img src="{render_fig_src('screen5-family-v3.png')}" alt="">
  </div>
</section>"""


def render_fig_src(name):
    dst, _ = prepare_image(find_image(name), [])
    return dst.relative_to(OUT).as_posix()


def toc_html(pages):
    items = ""
    for ch in BOOK["chapters"]:
        pg = pages.get(ch["no"], "")
        items += (f'<li><span class="no">{ch["num"]}</span><span class="t">{ch["title"]}'
                  f'<small>{ch.get("toc_sub", "")}</small></span><span class="pg">{pg}</span></li>')
    return f'<section class="toc"><h2>차례</h2><ol>{items}</ol></section>'


def front_html():
    p = ROOT / "chapters" / "front.html"
    return p.read_text(encoding="utf-8") if p.exists() else ""


def build_book():
    chapters = "".join(chapter_html(ch) for ch in BOOK["chapters"])
    pages = {}
    for _ in range(2):  # 1차: 쪽 번호 찾기 → 2차: 목차에 반영
        doc = cover_html() + toc_html(pages) + FIG_RE.sub(render_fig, front_html()) + chapters
        pdf = render(page(BOOK["title"], doc), "book")
        reader = PdfReader(str(pdf))
        found = {}
        for i, pg in enumerate(reader.pages):
            text = pg.extract_text() or ""
            for ch in BOOK["chapters"]:
                if ch["no"] not in found and ch["label"] in text and ch["title"].split("<")[0][:8] in text.replace("\n", " "):
                    found[ch["no"]] = i + 1
        if found == pages:
            break
        pages = found
    final = OUT / BOOK["book_pdf"]
    shutil.copy(pdf, final)
    print("통합본:", final, len(PdfReader(str(final)).pages), "쪽")


def main():
    args = sys.argv[1:]
    only_book = "--book" in args
    nums = [int(a) for a in args if a.isdigit()]
    if not only_book:
        for ch in BOOK["chapters"]:
            if nums and ch["no"] not in nums:
                continue
            pdf = render(page(f"{ch['num']}. {ch['title']}", chapter_html(ch)), ch["slug"])
            print(f"{ch['num']}장:", pdf, len(PdfReader(str(pdf)).pages), "쪽")
    if only_book or not nums:
        build_book()


if __name__ == "__main__":
    main()
