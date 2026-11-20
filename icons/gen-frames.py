#!/usr/bin/env python3
# Regenerate the icon-pack frame art: python3 icons/gen-frames.py icons/res/drawable-nodpi app/assets/ShareTechMono-Regular.ttf  (needs Pillow)
# Concept "periodic element" icon frame: tile chamfered 20 % at top-left and bottom-right.
import sys
from PIL import Image, ImageDraw, ImageFont
out, font = sys.argv[1], sys.argv[2]
S, SS = 432, 4                      # final size, supersampling
N = S * SS
ACC, INK, MUTED = (0xBB, 0x9A, 0xF7, 255), (0x05, 0x07, 0x0D, 255), (0x73, 0x7A, 0xA2, 255)

def tile(inset):
    a, b = (24 + inset) * SS, (408 - inset) * SS
    k = (b - a) * 0.20
    return [(a + k, a), (b, a), (b, b - k), (b - k, b), (a, b), (a, a + k)]

def save(img, name):
    img.resize((S, S), Image.LANCZOS).save(f"{out}/{name}.png")

BORDER = 11                          # 1.5 px at 70 px in the concept, scaled
back = Image.new("RGBA", (N, N), (0, 0, 0, 0)); ImageDraw.Draw(back).polygon(tile(0), fill=INK); save(back, "iconback")
mask = Image.new("RGBA", (N, N), (0, 0, 0, 255)); ImageDraw.Draw(mask).polygon(tile(BORDER), fill=(0, 0, 0, 0)); save(mask, "iconmask")
upon = Image.new("RGBA", (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(upon)
d.polygon(tile(0), fill=ACC)
upon_ring = upon.copy()
# punch the inner hole (drawing a transparent polygon does not erase in RGBA)
hole = Image.new("L", (N, N), 0); ImageDraw.Draw(hole).polygon(tile(BORDER), fill=255)
upon_ring.putalpha(Image.composite(Image.new("L", (N, N), 0), upon_ring.getchannel("A"), hole))
save(upon_ring, "iconupon")

# pack icon: a full element tile, "Tn" / 001 / HUD
pk = Image.new("RGBA", (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(pk)
d.polygon(tile(0), fill=ACC); d.polygon(tile(BORDER), fill=INK)
f_sym, f_sm = ImageFont.truetype(font, 168 * SS), ImageFont.truetype(font, 40 * SS)
d.text((372 * SS, 70 * SS), "001", font=f_sm, fill=MUTED, anchor="rt")
d.text((80 * SS, 300 * SS), "Tn", font=f_sym, fill=ACC, anchor="ls")
d.text((80 * SS, 368 * SS), "HUD", font=f_sm, fill=MUTED, anchor="ls")
save(pk, "ic_pack")
