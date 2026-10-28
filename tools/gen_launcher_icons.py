"""Generate Qingyu launcher icon resources from the approved brand artwork."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "imesdk" / "src" / "main" / "res"
BRAND_DOCS = ROOT / "docs" / "brand"
MASTER_SOURCE = BRAND_DOCS / "qingyu-icon-master.png"
MARK_SOURCE = BRAND_DOCS / "qingyu-glyph-transparent.png"

INK = (0x1A, 0x24, 0x21)
IVORY = (0xF6, 0xF4, 0xEF)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def clean_alpha(image: Image.Image, cutoff: int = 16) -> Image.Image:
    """Remove near-transparent generation noise without altering visible artwork."""
    rgba = image.convert("RGBA")
    red, green, blue, alpha = rgba.split()
    alpha = alpha.point(lambda value: 0 if value < cutoff else value)
    return Image.merge("RGBA", (red, green, blue, alpha))


def trim(image: Image.Image) -> Image.Image:
    bounds = image.getbbox()
    if bounds is None:
        raise ValueError("The transparent logo source contains no visible pixels")
    return image.crop(bounds)


def fit(image: Image.Image, target: int) -> Image.Image:
    scale = target / max(image.size)
    size = tuple(max(1, round(axis * scale)) for axis in image.size)
    return image.resize(size, Image.Resampling.LANCZOS)


def paste_center(canvas: Image.Image, artwork: Image.Image) -> None:
    x = (canvas.width - artwork.width) // 2
    y = (canvas.height - artwork.height) // 2
    canvas.alpha_composite(artwork, (x, y))


def rounded_plate(side: int, color: tuple[int, int, int]) -> Image.Image:
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    inset = round(side * 0.06)
    radius = round((side - 2 * inset) * 0.22)
    ImageDraw.Draw(canvas).rounded_rectangle(
        (inset, inset, side - inset - 1, side - inset - 1),
        radius=radius,
        fill=(*color, 255),
    )
    return canvas


def legacy_icon(mark: Image.Image, side: int, plate: tuple[int, int, int]) -> Image.Image:
    icon = rounded_plate(side, plate)
    paste_center(icon, fit(mark, round(side * 0.60)))
    return icon


def save(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    print(f"{path.relative_to(ROOT)}  {image.width}x{image.height}")


def main() -> None:
    if not MASTER_SOURCE.is_file() or not MARK_SOURCE.is_file():
        raise FileNotFoundError("Approved icon sources are missing from docs/brand")

    mark = trim(clean_alpha(Image.open(MARK_SOURCE)))

    BRAND_DOCS.mkdir(parents=True, exist_ok=True)
    save(RES / "drawable-nodpi" / "ic_sdk_toolbar_brand.png", mark)

    # Android adaptive icons use a 108 dp canvas and a 66 dp circular safe zone.
    # A 46% maximum edge keeps this diagonal feather inside that safe area.
    adaptive = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    paste_center(adaptive, fit(mark, round(432 * 0.46)))
    save(RES / "drawable-nodpi" / "ic_sdk_launcher_foreground.png", adaptive)
    save(RES / "drawable-nodpi" / "ic_sdk_launcher_debug_foreground.png", adaptive)

    # debug 变体与正式版保持同一底色：debug 包会发给维护者本人和测试者自用，
    # 底色若不同会导致「同一个应用在不同包上长得不一样」，且深色底在部分
    # 桌面与安装器上会被误读成图标损坏。区分改用应用名后缀而非底色。
    for name, plate in (
        ("ic_sdk_launcher", IVORY),
        ("ic_sdk_launcher_debug", IVORY),
    ):
        for density, side in DENSITIES.items():
            save(
                RES / f"drawable-{density}" / f"{name}.png",
                legacy_icon(mark, side, plate),
            )


if __name__ == "__main__":
    main()
