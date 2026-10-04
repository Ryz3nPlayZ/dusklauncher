"""The Dusk app icon: the launcher's dusk background in a 26-cell pixel plate.

Purple cloud bank on top, the sky falling through magenta and pink to the
peach glow, the diamond sun on a pale sea line, pink trees in the haze,
blocky dark trees rim-lit orange at both sides, and the orange grass edge on
dark purple dirt. Palette sampled from assets/background/dusk.

Writes icon-1024.png (feed it to `npx tauri icon`), plus the in-app copies:
the 26x26 plate for the mod and the 128 / 256 grid renders for the launcher
nav and the site.
"""
import os
from PIL import Image

G = 32                      # pixel grid
M = 3                       # transparent margin cells (≈9% like Apple's template)
OUT = 1024
img = Image.new('RGBA', (G, G), (0, 0, 0, 0))
px = img.load()

def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2],16), int(h[2:4],16), int(h[4:6],16), a)

BLACK = hexc('#000000')
# sky, top → horizon, as in the background
SKY = ['#1a123e', '#23184d', '#2f1e5d', '#442670', '#592a79', '#7e317c',
       '#b0437d', '#d95c79', '#e86f72', '#fc986d', '#ffa670']
CLOUD = hexc('#3b2268'); CLOUD_HI = hexc('#5c2a7a')
SEA = hexc('#d4bc9f')
# below the sea line the haze darkens toward the ground, pink trees in it
HAZE = ['#d38460', '#cc6069', '#bc5b63', '#a94668']
TREE_FAR = hexc('#a14364'); TREE_FAR_DK = hexc('#7f3565')
SUN = hexc('#ffc488'); SUN_RIM = hexc('#ffa46f'); SUN_CORE = hexc('#ffe2b8')
TREE = hexc('#322e4d'); TREE_DK = hexc('#262340'); RIM = hexc('#f4a265'); TRUNK = hexc('#351c35')
GRASS = hexc('#f8a360'); GRASS_LO = hexc('#e28d5e')
DIRT = [hexc('#261530'), hexc('#1f142d'), hexc('#2f1938'), hexc('#1f142d')]

lo, hi = M, G - M - 1       # inclusive bounds of the plate (26×26)
CORNER = [3, 2, 1]          # per-row inset from the corner edge

def inside(x, y):
    if x < lo or x > hi or y < lo or y > hi: return False
    dy = min(y - lo, hi - y); dx = min(x - lo, hi - x)
    if dy < len(CORNER) and dx < CORNER[dy]: return False
    return True

def interior(x, y):
    return all(inside(x+dx, y+dy) for dx in (-1,0,1) for dy in (-1,0,1))

def put(x, y, c):
    if interior(x, y): px[x, y] = c

# 1. plate: black everywhere inside the silhouette
for y in range(G):
    for x in range(G):
        if inside(x, y): px[x, y] = BLACK

ilo, ihi = lo + 1, hi - 1   # interior cells 4..27 (24×24)
SEA_Y = ilo + 14            # the pale sea line
GROUND = ilo + 19           # grass edge row

# 2. sky gradient down to the sea line, then haze below it
for y in range(ilo, GROUND):
    for x in range(ilo, ihi + 1):
        if y < SEA_Y:
            t = (y - ilo) / (SEA_Y - ilo)
            put(x, y, hexc(SKY[min(len(SKY) - 1, int(t * len(SKY)))]))
        elif y == SEA_Y:
            put(x, y, SEA)
        else:
            put(x, y, hexc(HAZE[min(len(HAZE) - 1, y - SEA_Y - 1)]))

# 3. cloud streaks in the purple band
for (x0, x1, y) in [(ilo + 2, ilo + 9, ilo + 2), (ilo + 5, ilo + 12, ilo + 3),
                    (ihi - 10, ihi - 3, ilo + 4), (ihi - 7, ihi, ilo + 5)]:
    for x in range(x0, x1 + 1): put(x, y, CLOUD)
for x in range(ilo + 6, ilo + 10): put(x, ilo + 3, CLOUD_HI)

# 4. the diamond sun, just off centre like the scene's, sitting on the sea
cx, cy, r = ilo + 10, SEA_Y - 3, 4
for y in range(cy - r, SEA_Y):
    for x in range(cx - r, cx + r + 1):
        d = abs(x - cx) + abs(y - cy)
        if d < r: put(x, y, SUN_CORE if d <= 1 else SUN)
        elif d == r: put(x, y, SUN_RIM)

# 5. pink trees in the haze: small square crowns on thin trunks
for (x, top, w) in [(ilo + 6, SEA_Y + 1, 3), (ilo + 13, SEA_Y, 3), (ilo + 16, SEA_Y + 2, 2)]:
    for y in range(top, top + 2):
        for dx in range(w): put(x + dx, y, TREE_FAR)
    for y in range(top + 2, GROUND): put(x + w // 2, y, TREE_FAR_DK)

# 6. foreground trees: square canopies of dark blocks, lit orange on the
#    sun side, on dark trunks down to the grass
def canopy(x0, y0, w, h, lit_left):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            edge_top = y == y0
            edge_side = x == (x0 if lit_left else x0 + w - 1)
            put(x, y, RIM if edge_top or edge_side else (TREE_DK if (x + y) % 5 == 0 else TREE))

def trunk(x, y0):
    for y in range(y0, GROUND): put(x, y, TRUNK); put(x + 1, y, TRUNK)

# left tree, against the plate edge
canopy(ilo, ilo + 6, 5, 6, lit_left=False)
canopy(ilo, ilo + 11, 3, 3, lit_left=False)
trunk(ilo + 1, ilo + 12)
# right tree: taller, two stacked blocks
canopy(ihi - 4, ilo + 3, 5, 5, lit_left=True)
canopy(ihi - 6, ilo + 8, 7, 5, lit_left=True)
trunk(ihi - 2, ilo + 13)

# 7. ground: the orange grass edge, a step up at the right, dirt below
for x in range(ilo, ihi + 1):
    step = 1 if x >= ihi - 5 else 0
    put(x, GROUND - step, GRASS if x % 4 else GRASS_LO)
    for y in range(GROUND - step + 1, ihi + 1):
        put(x, y, DIRT[(x * 3 + y * 5) % len(DIRT)])

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.abspath(os.path.join(here, '..', '..', '..'))
img.resize((OUT, OUT), Image.NEAREST).save(os.path.join(here, 'icon-1024.png'))
img.crop((lo, lo, hi + 1, hi + 1)).save(
    os.path.join(root, 'client-mod/src/main/resources/assets/duskclient/textures/gui/dusk_mark.png'))
img.resize((128, 128), Image.NEAREST).save(os.path.join(root, 'launcher/src/assets/brand/dusk-mark.png'))
img.resize((128, 128), Image.NEAREST).save(os.path.join(root, 'site/img/dusk-mark.png'))
img.resize((256, 256), Image.NEAREST).save(os.path.join(root, 'site/img/icon.png'))
print('ok')
