"""把 logo/ 里的 .ico 转成安卓各尺寸的启动图标；没有 ico 时生成一个占位图标。"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

icos = sorted((ROOT / "logo").glob("*.ico"))
if icos:
    src = Image.open(icos[0])
    # ico 里可能有多个尺寸，取最大的
    if hasattr(src, "ico"):
        src.size = max(src.ico.sizes())
    src = src.convert("RGBA")
    print(f"使用图标 {icos[0].name}，原始尺寸 {src.size}")
else:
    print("logo/ 里没有 ico，使用占位图标")
    src = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    d = ImageDraw.Draw(src)
    d.rounded_rectangle((0, 0, 511, 511), radius=112, fill=(0, 122, 255, 255))
    d.ellipse((136, 136, 376, 376), fill=(255, 255, 255, 255))

for density, px in SIZES.items():
    out = RES / f"mipmap-{density}" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    src.resize((px, px), Image.LANCZOS).save(out)
