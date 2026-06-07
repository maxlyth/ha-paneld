#!/usr/bin/env python3
"""Generate the ha-paneld horizontal wordmark: the app-icon glyph + "ha-paneld" set in a clean,
legible sans-serif. Emits TWO theme variants from the same glyph + font in one run:

  - res/drawable-nodpi/wordmark.png        dark text  — for LIGHT backgrounds (Android day theme)
  - res/drawable-night-nodpi/wordmark.png  light text — for DARK backgrounds (Android night theme)

`R.drawable.wordmark` then resolves per the panel's light/dark setting automatically, and the README
references both files explicitly via a <picture> element. The glyph is cropped to its visible content
and the text height is set as a fraction of the glyph height, so the icon leads and the balance is
independent of the icon's internal padding / font metrics.

Deps: cairosvg, Pillow, and a bold sans-serif TTF passed as the first argument. Usage:
    python3 scripts/gen-wordmark.py <path-to-font.ttf>
"""
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

ICON = "app/src/main/assets/icon.svg"
FONT = sys.argv[1] if len(sys.argv) > 1 else "wordmark-font.ttf"
TEXT = "ha-paneld"
# (text colour, output path) — one entry per theme. The glyph is shared; only the text ink changes.
VARIANTS = [
    ((29, 33, 38, 255), "app/src/main/res/drawable-nodpi/wordmark.png"),        # #1D2126 on light bg
    ((232, 236, 243, 255), "app/src/main/res/drawable-night-nodpi/wordmark.png"),  # #E8ECF3 on dark bg
]
H = 200                       # visible glyph height (px) after cropping its padding
TEXT_FRAC = 0.72              # text ink height as a fraction of glyph height (< 1 → icon leads)
GAP_FRAC = 0.28               # gap between glyph and text, as a fraction of glyph width

# 1. Render the app-icon glyph large, crop to its visible content, scale to H (shared by both variants).
subprocess.run(["cairosvg", ICON, "--output-width", "512", "--output-height", "512", "-o", "/tmp/glyph.png"], check=True)
g = Image.open("/tmp/glyph.png").convert("RGBA")
g = g.crop(g.getbbox())
g = g.resize((round(g.width * H / g.height), H), Image.LANCZOS)

font = ImageFont.truetype(FONT, 200)
bb = ImageDraw.Draw(Image.new("RGBA", (4, 4))).textbbox((0, 0), TEXT, font=font)
tw, th = bb[2] - bb[0], bb[3] - bb[1]
ink = round(H * TEXT_FRAC)
gap = round(g.width * GAP_FRAC)
pad = round(H * 0.04)

for color, out_path in VARIANTS:
    # 2. Render the text big, trim, then scale to exactly TEXT_FRAC*H tall (decouples from font metrics).
    txt = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    ImageDraw.Draw(txt).text((-bb[0], -bb[1]), TEXT, font=font, fill=color)
    txt = txt.resize((round(tw * ink / th), ink), Image.LANCZOS)

    # 3. Lock up: glyph left, gap, text vertically centred.
    canvas = Image.new("RGBA", (g.width + gap + txt.width, H), (0, 0, 0, 0))
    canvas.alpha_composite(g, (0, 0))
    canvas.alpha_composite(txt, (g.width + gap, (H - txt.height) // 2))

    # 4. Trim + small padding, save.
    canvas = canvas.crop(canvas.getbbox())
    out = Image.new("RGBA", (canvas.width + 2 * pad, canvas.height + 2 * pad), (0, 0, 0, 0))
    out.alpha_composite(canvas, (pad, pad))
    out.save(out_path)
    print(f"wrote {out_path} {out.size}  (aspect {out.width / out.height:.2f}:1)")
