#!/usr/bin/env python3
"""Generate the ha-paneld horizontal wordmark: the app-icon glyph + "ha-paneld" set in
Source Sans 3 Bold (a clean humanist sans; OFL), light-coloured for the dark App UI. Outputs a
transparent PNG used by the launcher screen.

The glyph is cropped to its visible content and the text height is set as a fraction of the glyph
height, so the icon leads and the balance is independent of the icon's internal padding / font metrics.

Deps: cairosvg, Pillow, and the Source Sans 3 Bold TTF (OFL —
https://github.com/adobe-fonts/source-sans). Usage:
    python3 scripts/gen-wordmark.py [path-to-SourceSans3-Bold.ttf]
"""
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

ICON = "app/src/main/assets/icon.svg"
OUT = "app/src/main/res/drawable-nodpi/wordmark.png"
FONT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/atkinson.ttf"
TEXT = "ha-paneld"
LIGHT = (232, 236, 243, 255)  # #E8ECF3 — reads on the dark UI
H = 200                       # visible glyph height (px) after cropping its padding
TEXT_FRAC = 0.72              # text ink height as a fraction of glyph height (< 1 → icon leads)
GAP_FRAC = 0.28               # gap between glyph and text, as a fraction of glyph width

# 1. Render the app-icon glyph large, crop to its visible content, scale to H.
subprocess.run(["cairosvg", ICON, "--output-width", "512", "--output-height", "512", "-o", "/tmp/glyph.png"], check=True)
g = Image.open("/tmp/glyph.png").convert("RGBA")
g = g.crop(g.getbbox())
g = g.resize((round(g.width * H / g.height), H), Image.LANCZOS)

# 2. Render the text big, trim, then scale to exactly TEXT_FRAC*H tall (decouples from font metrics).
font = ImageFont.truetype(FONT, 200)
bb = ImageDraw.Draw(Image.new("RGBA", (4, 4))).textbbox((0, 0), TEXT, font=font)
tw, th = bb[2] - bb[0], bb[3] - bb[1]
txt = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
ImageDraw.Draw(txt).text((-bb[0], -bb[1]), TEXT, font=font, fill=LIGHT)
ink = round(H * TEXT_FRAC)
txt = txt.resize((round(tw * ink / th), ink), Image.LANCZOS)

# 3. Lock up: glyph left, gap, text vertically centred.
gap = round(g.width * GAP_FRAC)
canvas = Image.new("RGBA", (g.width + gap + txt.width, H), (0, 0, 0, 0))
canvas.alpha_composite(g, (0, 0))
canvas.alpha_composite(txt, (g.width + gap, (H - txt.height) // 2))

# 4. Trim + small padding, save.
canvas = canvas.crop(canvas.getbbox())
pad = round(H * 0.04)
out = Image.new("RGBA", (canvas.width + 2 * pad, canvas.height + 2 * pad), (0, 0, 0, 0))
out.alpha_composite(canvas, (pad, pad))
out.save(OUT)
print(f"wrote {OUT} {out.size}  (aspect {out.width / out.height:.2f}:1)")
