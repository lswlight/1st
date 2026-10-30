"""앱 아이콘 생성 스크립트.

어두운 배경 위에 흰 테두리 사진 9장을 3x3 으로 살짝 기울여 놓은 아이콘을 그린다.
사진 속 장면(노을, 야경, 물 반사 등)은 모두 코드로 그린다.

    python3 tools/make_icon.py

결과:
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png  (적응형 아이콘 전경, 432px)
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png  (적응형 아이콘 배경, 432px)
  tools/icon_preview.png                                      (미리보기, 1024px)
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/mipmap-xxxhdpi"
S = 4  # 고해상도로 그린 뒤 줄여서 계단 현상을 없앤다

rng = random.Random(7)


def gradient(w, h, stops):
    """세로 그라데이션. stops = [(위치 0~1, (r,g,b)), ...]"""
    ys = np.linspace(0, 1, h)
    out = np.zeros((h, w, 3))
    for c in range(3):
        out[:, :, c] = np.interp(ys, [p for p, _ in stops], [col[c] for _, col in stops])[:, None]
    return Image.fromarray(out.astype(np.uint8))


def skyline(d, w, base, color, rnd, lit=True, max_h=0.45):
    x = 0
    while x < w:
        bw = rnd.randint(int(w * 0.05), int(w * 0.12))
        bh = rnd.randint(int(w * 0.10), int(w * max_h))
        d.rectangle([x, base - bh, x + bw, base], fill=color)
        if rnd.random() < 0.3:  # 첨탑
            d.rectangle([x + bw // 2 - 2 * S, base - bh - w * 0.06, x + bw // 2 + 2 * S, base - bh], fill=color)
        if lit:
            for wy in range(int(base - bh + 6 * S), int(base - 4 * S), 7 * S):
                for wx in range(x + 3 * S, x + bw - 3 * S, 6 * S):
                    if rnd.random() < 0.65:
                        d.rectangle([wx, wy, wx + 2 * S, wy + 3 * S], fill=rnd.choice([(255, 214, 140), (255, 236, 190), (250, 190, 110)]))
        x += bw + rnd.randint(0, 3 * S)


def reflect(img, horizon, strength=0.55, tint=(10, 20, 35)):
    """horizon 아래를 위쪽 장면의 뒤집힌 반사로 채운다."""
    w, h = img.size
    top = img.crop((0, 0, w, horizon))
    refl = top.transpose(Image.FLIP_TOP_BOTTOM).resize((w, h - horizon))
    refl = refl.filter(ImageFilter.GaussianBlur(1.5 * S))
    dark = Image.new("RGB", refl.size, tint)
    refl = Image.blend(refl, dark, 1 - strength)
    # 잔물결
    d = ImageDraw.Draw(refl)
    for y in range(0, refl.size[1], 5 * S):
        d.line([(0, y), (w, y)], fill=tint, width=S)
    img.paste(refl, (0, horizon))


def scene(kind, size):
    w = h = size
    rnd = random.Random(kind * 31 + 5)
    if kind == 0:  # 바다 노을
        img = gradient(w, h, [(0, (40, 52, 98)), (0.35, (196, 92, 74)), (0.55, (255, 168, 80)), (1, (255, 168, 80))])
        d = ImageDraw.Draw(img)
        hz = int(h * 0.58)
        d.ellipse([w * 0.42, hz - w * 0.12, w * 0.62, hz + w * 0.08], fill=(255, 226, 150))
        reflect(img, hz, 0.6, (30, 20, 30))
        d = ImageDraw.Draw(img)
        for i in range(8):
            y = hz + 6 * S + i * 8 * S
            d.line([(w * 0.47 - i * 2 * S, y), (w * 0.57 + i * 2 * S, y)], fill=(255, 210, 130), width=2 * S)
    elif kind == 1:  # 야경 + 강 반사
        img = gradient(w, h, [(0, (8, 12, 30)), (0.55, (26, 36, 70)), (1, (26, 36, 70))])
        d = ImageDraw.Draw(img)
        hz = int(h * 0.6)
        skyline(d, w, hz, (12, 14, 24), rnd, max_h=0.5)
        reflect(img, hz, 0.7, (6, 10, 22))
    elif kind == 2:  # 푸른 황혼 + 나무
        img = gradient(w, h, [(0, (34, 70, 130)), (0.6, (120, 150, 190)), (0.75, (240, 170, 110)), (1, (30, 30, 40))])
        d = ImageDraw.Draw(img)
        d.rectangle([0, h * 0.78, w, h], fill=(18, 20, 28))
        d.rectangle([w * 0.70, h * 0.35, w * 0.73, h * 0.80], fill=(16, 18, 24))
        for _ in range(26):
            cx, cy = w * 0.72 + rnd.uniform(-0.17, 0.17) * w, h * 0.35 + rnd.uniform(-0.12, 0.12) * h
            r = rnd.uniform(0.05, 0.09) * w
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(16, 18, 24))
    elif kind == 3:  # 산 노을
        img = gradient(w, h, [(0, (70, 40, 90)), (0.45, (230, 110, 80)), (0.7, (255, 190, 110)), (1, (255, 190, 110))])
        d = ImageDraw.Draw(img)
        for layer, (col, base) in enumerate([((120, 60, 80), 0.62), ((70, 36, 60), 0.72), ((30, 18, 34), 0.84)]):
            pts = [(0, h)]
            x = 0
            while x <= w:
                pts.append((x, h * base - rnd.uniform(0, 0.16) * h))
                x += rnd.randint(int(w * 0.08), int(w * 0.18))
            pts += [(w, h * base), (w, h)]
            d.polygon(pts, fill=col)
    elif kind == 4:  # 고층 빌딩 야경 (참고 이미지 중앙 느낌)
        img = gradient(w, h, [(0, (10, 14, 26)), (0.5, (30, 40, 66)), (1, (30, 40, 66))])
        d = ImageDraw.Draw(img)
        hz = int(h * 0.55)
        skyline(d, w, hz, (20, 22, 34), rnd, max_h=0.42)
        for bx, bh in [(0.30, 0.42), (0.45, 0.50), (0.58, 0.38)]:  # 앞쪽 랜드마크 빌딩
            x0 = w * bx
            d.rectangle([x0, hz - h * bh, x0 + w * 0.09, hz], fill=(14, 16, 26))
            d.polygon([(x0, hz - h * bh), (x0 + w * 0.045, hz - h * (bh + 0.07)), (x0 + w * 0.09, hz - h * bh)], fill=(14, 16, 26))
            for wy in range(int(hz - h * bh + 4 * S), hz - 3 * S, 5 * S):
                d.line([(x0 + 3 * S, wy), (x0 + w * 0.09 - 3 * S, wy)], fill=(250, 220, 160), width=S)
        reflect(img, hz, 0.75, (8, 12, 24))
    elif kind == 5:  # 잔교가 있는 황혼 바다
        img = gradient(w, h, [(0, (60, 70, 120)), (0.5, (220, 140, 120)), (0.62, (250, 200, 150)), (1, (250, 200, 150))])
        hz = int(h * 0.62)
        reflect(img, hz, 0.55, (40, 40, 70))
        d = ImageDraw.Draw(img)
        d.polygon([(w * 0.1, h), (w * 0.45, hz + 2 * S), (w * 0.5, hz + 2 * S), (w * 0.35, h)], fill=(30, 26, 36))
        for i in range(6):
            t = i / 6
            x = w * (0.12 + 0.33 * t)
            y = h - (h - hz) * t
            d.line([(x, y), (x, y - h * 0.08 * (1 - t * 0.6))], fill=(30, 26, 36), width=2 * S)
    elif kind == 6:  # 별 + 스카이라인
        img = gradient(w, h, [(0, (6, 8, 22)), (0.7, (40, 30, 70)), (1, (90, 50, 80))])
        d = ImageDraw.Draw(img)
        for _ in range(60):
            x, y, r = rnd.uniform(0, w), rnd.uniform(0, h * 0.6), rnd.choice([0.4, 0.4, 0.8]) * S
            d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 235))
        d.ellipse([w * 0.72, h * 0.12, w * 0.84, h * 0.24], fill=(250, 240, 210))
        skyline(d, w, h, (10, 10, 20), rnd, max_h=0.35)
    elif kind == 7:  # 금빛 구름
        img = gradient(w, h, [(0, (80, 110, 170)), (0.5, (240, 180, 120)), (1, (190, 90, 70))])
        d = ImageDraw.Draw(img)
        for _ in range(9):
            cx, cy = rnd.uniform(0, w), rnd.uniform(h * 0.15, h * 0.7)
            for _ in range(5):
                rx, ry = rnd.uniform(0.08, 0.16) * w, rnd.uniform(0.03, 0.06) * h
                ox = rnd.uniform(-0.1, 0.1) * w
                d.ellipse([cx + ox - rx, cy - ry, cx + ox + rx, cy + ry], fill=(255, 214, 160))
        img = img.filter(ImageFilter.GaussianBlur(2 * S))
        d = ImageDraw.Draw(img)
        d.rectangle([0, h * 0.86, w, h], fill=(40, 26, 30))
    else:  # 다리 야경
        img = gradient(w, h, [(0, (12, 18, 40)), (0.55, (40, 60, 100)), (1, (40, 60, 100))])
        d = ImageDraw.Draw(img)
        hz = int(h * 0.58)
        skyline(d, w, hz, (16, 20, 34), rnd, max_h=0.25)
        d.rectangle([0, hz - 0.1 * h, w, hz - 0.08 * h], fill=(20, 22, 32))
        for i in range(12):
            x = (i + 0.5) * w / 12
            d.ellipse([x - S, hz - 0.1 * h - S, x + S, hz - 0.1 * h + S], fill=(255, 220, 140))
        for px in (0.25, 0.75):
            d.rectangle([w * px - 3 * S, hz - 0.3 * h, w * px + 3 * S, hz], fill=(20, 22, 32))
            for t in np.linspace(0, 1, 7):
                d.line([(w * px, hz - 0.3 * h), (w * (px - 0.22 + 0.44 * t), hz - 0.09 * h)], fill=(60, 70, 100), width=S)
        reflect(img, hz, 0.7, (8, 14, 30))
    return img


def polaroid(kind, photo_px):
    """흰 테두리 사진 한 장 (RGBA)"""
    border = int(photo_px * 0.07)
    inner = scene(kind, photo_px - border * 2)
    card = Image.new("RGBA", (photo_px, photo_px), (250, 250, 248, 255))
    card.paste(inner, (border, border))
    return card


def compose(canvas_px, grid_px):
    """투명 캔버스 중앙에 grid_px 크기의 3x3 사진 묶음을 그린다."""
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    cell = grid_px / 3
    photo_px = int(cell * 0.94)  # 사진 사이에 배경이 살짝 보이도록
    origin = (canvas_px - grid_px) / 2
    order = [0, 1, 2, 3, 4, 5, 6, 7, 8]
    # 가운데 사진(야경)을 마지막에 그려 맨 위에 오도록
    draw_order = [0, 2, 6, 8, 1, 3, 5, 7, 4]
    for idx in draw_order:
        r, c = divmod(idx, 3)
        card = polaroid(order[idx], photo_px)
        angle = rng.uniform(-7, 7) if idx != 4 else 3
        card = card.rotate(angle, resample=Image.BICUBIC, expand=True)
        cx = origin + (c + 0.5) * cell + rng.uniform(-0.03, 0.03) * cell
        cy = origin + (r + 0.5) * cell + rng.uniform(-0.03, 0.03) * cell
        pos = (int(cx - card.width / 2), int(cy - card.height / 2))
        # 그림자
        shadow = Image.new("RGBA", card.size, (0, 0, 0, 0))
        shadow.putalpha(card.getchannel("A").point(lambda a: int(a * 0.55)))
        shadow = shadow.filter(ImageFilter.GaussianBlur(photo_px * 0.04))
        canvas.alpha_composite(shadow, (pos[0] + int(photo_px * 0.02), pos[1] + int(photo_px * 0.04)))
        canvas.alpha_composite(card, pos)
    return canvas


def background(px):
    """짙은 회색 배경 + 은은한 중앙 빛 + 종이 질감"""
    yy, xx = np.mgrid[0:px, 0:px] / px
    dist = np.sqrt((xx - 0.5) ** 2 + (yy - 0.45) ** 2)
    base = 58 - dist * 40
    noise = np.random.default_rng(3).normal(0, 3, (px, px))
    v = np.clip(base + noise, 0, 255)
    img = np.stack([v, v, v * 1.03], axis=-1).clip(0, 255).astype(np.uint8)
    return Image.fromarray(img).convert("RGBA")


def main():
    big = 432 * S
    # 적응형 아이콘: 108dp 중 가운데 약 72dp 가 보이므로 사진 묶음을 그 안에 맞춘다.
    fg_big = compose(big, int(big * 0.50))
    fg = fg_big.resize((432, 432), Image.LANCZOS)
    bg = background(432)
    RES.mkdir(parents=True, exist_ok=True)
    fg.save(RES / "ic_launcher_foreground.png")
    bg.convert("RGB").save(RES / "ic_launcher_background.png")

    # 미리보기: 보이는 영역(72dp)만 잘라 둥근 사각형으로
    full = background(big)
    full.alpha_composite(fg_big)
    crop = int(big * 18 / 108)
    view = full.crop((crop, crop, big - crop, big - crop)).resize((1024, 1024), Image.LANCZOS)
    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=230, fill=255)
    preview = Image.new("RGBA", (1024, 1024), (255, 255, 255, 255))
    preview.paste(view, (0, 0), mask)
    preview.convert("RGB").save(ROOT / "tools/icon_preview.png")


if __name__ == "__main__":
    main()
