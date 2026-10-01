"""Build launcher assets from the generated CRT television illustration."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(__file__).with_name("front-crt-dvd-poster-contained.png")
RES = ROOT / "app" / "src" / "main" / "res"
MIPMAP_SIZES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def illustration():
    image = Image.open(SOURCE).convert("RGBA")
    # Generated transparency may contain barely visible stray pixels far from the art.
    visible = image.getchannel("A").point(lambda alpha: 255 if alpha >= 8 else 0)
    left, top, right, bottom = visible.getbbox()
    padding = 12
    return image.crop((max(0, left - padding), max(0, top - padding),
                       min(image.width, right + padding), min(image.height, bottom + padding)))


def television(source, size, fraction):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    limit = round(size * fraction)
    ratio = min(limit / source.width, limit / source.height)
    art = source.resize((round(source.width * ratio), round(source.height * ratio)), Image.Resampling.LANCZOS)
    layer.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return layer


def background(size, shape):
    base = Image.new("RGBA", (size, size))
    pixels = base.load()
    for y in range(size):
        t = y / max(1, size - 1)
        color = (round(244 - 30 * t), round(219 - 57 * t), round(176 - 77 * t), 255)
        for x in range(size):
            pixels[x, y] = color

    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    draw.ellipse((size * .12, size * .18, size * .88, size * .94), fill=(255, 255, 255, 36))
    base = Image.alpha_composite(base, glow.filter(ImageFilter.GaussianBlur(size * .16)))

    if shape == "square":
        return base
    mask = Image.new("L", (size, size))
    draw = ImageDraw.Draw(mask)
    if shape == "round":
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=round(size * .22), fill=255)
    base.putalpha(mask)
    return base


def launcher(source, size, shape="rounded", tv_fraction=.79):
    return Image.alpha_composite(background(size, shape), television(source, size, tv_fraction))


def main():
    source = illustration()
    for density, (legacy_size, adaptive_size) in MIPMAP_SIZES.items():
        directory = RES / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        launcher(source, legacy_size).save(directory / "ic_launcher.png", optimize=True)
        launcher(source, legacy_size, "round", .73).save(
            directory / "ic_launcher_round.webp", lossless=True, method=6
        )
        television(source, adaptive_size, .57).save(
            directory / "tv_launcher_foreground.png", optimize=True
        )

    launcher(source, 600, "round", .73).save(RES / "drawable-nodpi" / "ic_logo.png", optimize=True)
    launcher(source, 512, "square", .79).save(ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png", optimize=True)
    launcher(source, 512).save(Path(__file__).with_name("icon-preview.png"), optimize=True)


if __name__ == "__main__":
    main()
