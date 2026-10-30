"""앱 아이콘 생성 스크립트.

tools/icon_source.png (1024px, 크림색 바탕 위 초록 앨범) 를 안드로이드 적응형 아이콘으로 바꾼다.

    python3 tools/make_icon.py

결과:
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png  (전경, 432px)
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png  (배경, 432px)
  tools/icon_preview.png                                      (미리보기, 1024px)
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/mipmap-xxxhdpi"
SOURCE = ROOT / "tools/icon_source.png"

# 적응형 아이콘은 108dp 중 가운데 72dp 정도가 보인다.
# 앨범(초록 책)이 108dp 중 46dp 를 차지하도록 원본을 잘라 맞춘다 (원본 이미지와 같은 여백 비율).
ALBUM_DP = 46


def main():
    src = Image.open(SOURCE).convert("RGB")
    a = np.array(src).astype(int)
    green = (a[:, :, 1] - a[:, :, 0] > 15) & (a[:, :, 0] < 80)
    ys, xs = np.where(green)
    cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    album_px = max(xs.max() - xs.min(), ys.max() - ys.min())
    window = int(album_px * 108 / ALBUM_DP)

    # 크림색 바탕 색 (원본 위쪽 가운데)
    cream = src.getpixel((src.width // 2, int(ys.min() * 0.5)))

    # 앨범을 가운데 두고 window 크기로 자른다. 원본 밖은 크림색으로 채운다.
    canvas = Image.new("RGB", (window, window), cream)
    left, top = int(cx - window / 2), int(cy - window / 2)
    canvas.paste(src, (-left, -top))
    # 원본 바깥쪽 흰 여백과 둥근 모서리가 보이지 않도록 크림색 판 밖은 크림색으로 덮는다.
    arr = np.array(canvas)
    light = (arr.sum(axis=2) > 245 * 3)
    arr[light] = cream
    canvas = Image.fromarray(arr)

    RES.mkdir(parents=True, exist_ok=True)
    canvas.resize((432, 432), Image.LANCZOS).save(RES / "ic_launcher_foreground.png")
    Image.new("RGB", (432, 432), cream).save(RES / "ic_launcher_background.png")

    # 미리보기: 보이는 72dp 영역을 둥근 사각형으로
    crop = int(window * 18 / 108)
    view = canvas.crop((crop, crop, window - crop, window - crop)).resize((1024, 1024), Image.LANCZOS)
    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=230, fill=255)
    preview = Image.new("RGB", (1024, 1024), (255, 255, 255))
    preview.paste(view, (0, 0), mask)
    preview.save(ROOT / "tools/icon_preview.png")


if __name__ == "__main__":
    main()
