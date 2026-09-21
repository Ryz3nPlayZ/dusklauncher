from PIL import Image

G = 32                      # pixel grid
M = 3                       # transparent margin cells (≈9% like Apple's template)
OUT = 1024
img = Image.new('RGBA', (G, G), (0, 0, 0, 0))
px = img.load()

def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2],16), int(h[2:4],16), int(h[4:6],16), a)

BLACK = hexc('#000000')
# dusk sky, top → horizon (token palette: purple family → gold accent)
SKY = ['#120a24','#1c1038','#2a1650','#3b1d68','#4e2578','#663080',
       '#7f3a82','#9a4676','#b4536a','#cc6350','#e0783a','#f0942a',
       '#f9ac1c']
GROUND = hexc('#0e0916')
HILL   = hexc('#160f22')
SUN    = hexc('#ffc600'); SUN_HI = hexc('#fffbcd'); SUN_LO = hexc('#de8105')
STAR   = hexc('#e9d8ff')

lo, hi = M, G - M - 1       # inclusive bounds of the plate (26×26)
R = 5                       # stepped corner: rows of cells to skip per corner
CORNER = [3,2,1]        # per-row inset from the corner edge

def inside(x, y):
    if x < lo or x > hi or y < lo or y > hi: return False
    dy = min(y - lo, hi - y); dx = min(x - lo, hi - x)
    if dy < len(CORNER) and dx < CORNER[dy]: return False
    return True

# 1. plate: black everywhere inside the silhouette
for y in range(G):
    for x in range(G):
        if inside(x, y): px[x, y] = BLACK

# 2. interior = plate shrunk by 1 cell outline
def interior(x, y):
    return all(inside(x+dx, y+dy) for dx in (-1,0,1) for dy in (-1,0,1))

ilo, ihi = lo + 1, hi - 1   # interior rows lo+1 .. hi-1 (24 rows)
HORIZON = ihi - 6           # ground starts here
for y in range(ilo, ihi + 1):
    for x in range(ilo, ihi + 1):
        if not interior(x, y): continue
        if y >= HORIZON:
            px[x, y] = GROUND
        else:
            t = (y - ilo) / (HORIZON - 1 - ilo)
            px[x, y] = hexc(SKY[min(len(SKY)-1, int(t * len(SKY)))])

# 3. sun: pixel semicircle (r=6) resting on the horizon, centred
WIDTHS = [12, 12, 10, 10, 8, 4]          # row widths from the horizon upward
cxi = (ilo + ihi + 1) // 2               # first cell right of centre
for k, w in enumerate(WIDTHS):
    y = HORIZON - 1 - k
    for x in range(cxi - w // 2, cxi + w // 2):
        if interior(x, y):
            px[x, y] = SUN
            if k in (2, 3) and cxi - 2 <= x < cxi + 2: px[x, y] = SUN_HI

# 4. ground edge: one slightly lighter row so the horizon has an edge
for x in range(ilo, ihi + 1):
    if interior(x, HORIZON): px[x, HORIZON] = HILL

# 5. a few stars high in the sky
for (x, y) in [(ilo+4, ilo+3), (ihi-5, ilo+1), (ilo+11, ilo+6), (ihi-2, ilo+5)]:
    if interior(x, y): px[x, y] = STAR

big = img.resize((OUT, OUT), Image.NEAREST)
big.save('icon-1024.png')
img.resize((256, 256), Image.NEAREST).save('preview-256.png')
print('ok')
