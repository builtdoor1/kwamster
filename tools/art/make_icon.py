"""Draws the KWAMSTER mod icon: a dark rounded tile with a pixel-art violet K
whose arms end in a crosshair, plus a gold 3-block pip row. Rendered at 512 px on
a 32-cell grid so every edge stays pixel-crisp, then downsampled to 128 px."""
import sys
from PIL import Image, ImageDraw

OUT = sys.argv[1]
N = 32            # design grid
S = 16            # px per cell at 512
img = Image.new("RGBA", (N * S, N * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

def cell(x, y, c):
    d.rectangle([x * S, y * S, (x + 1) * S - 1, (y + 1) * S - 1], fill=c)

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            cell(x, y, c)

BG = (17, 19, 26, 255)
BG2 = (27, 30, 41, 255)
VIOLET = (139, 92, 246, 255)
VIOLET_D = (96, 58, 196, 255)
VIOLET_L = (180, 150, 255, 255)
GOLD = (250, 204, 21, 255)
WHITE = (240, 242, 248, 255)

# rounded tile with a subtle inner border
d.rounded_rectangle([0, 0, N * S - 1, N * S - 1], radius=6 * S, fill=BG)
d.rounded_rectangle([S, S, N * S - 1 - S, N * S - 1 - S], radius=5 * S, outline=BG2, width=S)

# the K: stem
rect(8, 7, 11, 24, VIOLET)
rect(8, 7, 8, 24, VIOLET_L)            # left highlight
rect(11, 7, 11, 24, VIOLET_D)          # right shade
# upper arm (diagonal up-right)
for i in range(7):
    rect(12 + i, 13 - i, 15 + i, 16 - i, VIOLET)
    cell(12 + i, 13 - i, VIOLET_L)
# lower arm (diagonal down-right)
for i in range(6):
    rect(12 + i, 16 + i, 15 + i, 19 + i, VIOLET)
    cell(15 + i, 19 + i, VIOLET_D)

# crosshair at the top of the upper arm
cx, cy = 23, 8
rect(cx - 4, cy, cx - 2, cy, WHITE)
rect(cx + 2, cy, cx + 4, cy, WHITE)
rect(cx, cy - 4, cx, cy - 2, WHITE)
rect(cx, cy + 2, cx, cy + 4, WHITE)
cell(cx, cy, GOLD)

# three gold pips (3-block)
for x in (8, 11, 14):
    rect(x, 26, x + 1, 27, GOLD)

small = img.resize((128, 128), Image.LANCZOS)
small.save(OUT)
img.resize((256, 256), Image.LANCZOS).save(OUT.replace(".png", "_256.png"))
print("wrote", OUT)
