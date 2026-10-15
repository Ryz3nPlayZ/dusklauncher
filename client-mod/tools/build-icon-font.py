#!/usr/bin/env python3
"""Builds assets/duskclient/font/icons.ttf: the Icons glyphs as Phosphor
vector icons (MIT, github.com/phosphor-icons/core), one per Icons constant
in the Private Use Area from U+E000 in declaration order, so the modern look
draws them as text in the ui fonts. Run from the repo root after `npm ci` in
launcher/ whenever Icons gains a constant:

    python3 client-mod/tools/build-icon-font.py
"""
import re
import sys
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.svgLib.path import parse_path

ROOT = Path(__file__).resolve().parents[2]
ICONS = ROOT / "client-mod/src/main/java/dev/dusk/client/gui/Icons.java"
DEFS = ROOT / "launcher/node_modules/@phosphor-icons/react/dist/defs"
OUT = ROOT / "client-mod/src/main/resources/assets/duskclient/font/icons.ttf"

# Icons constant -> Phosphor icon (bold weight unless given)
PHOSPHOR = {
    "GRID": "SquaresFour", "PERSON": "User", "PEOPLE": "Users", "GEAR": "GearSix",
    "SLIDERS": "SlidersHorizontal", "HUD": "Layout", "EYE": "Eye", "ARROW": "ArrowRight",
    "DOTS": "DotsThree", "STAR": "Star", "BACK": "CaretLeft", "NEXT": "CaretRight",
    "LIST": "ListBullets", "SEARCH": "MagnifyingGlass", "GLOBE": "Globe", "REALMS": "CastleTurret",
    "ACCESS": "PersonArmsSpread", "CLOSE": "X", "CHECK": "Check", "CROSS": "XCircle",
    "RESET": "ArrowCounterClockwise", "PACK": "Package", "SHADER": "Sun", "MOVE": "ArrowsOutCardinal",
    "DOWN": "CaretDown", "MEDIA": "MonitorPlay", "PLAY": ("Play", "fill"), "PAUSE": ("Pause", "fill"),
    "FOLDER": "Folder", "CHAT": "ChatCircle", "GIFT": "Gift", "SPARKLE": "Sparkle",
    "RUN": "PersonSimpleRun", "WRENCH": "Wrench", "PALETTE": "Palette", "SHIRT": "TShirt",
    "MONITOR": "Monitor", "KEYBOARD": "Keyboard", "PIN": "MapPin", "CHART": "ChartBar", "FLAG": "Flag",
}

# Metrics copied from ui.ttf (1000 upm drawn at size 9.4) so the glyphs sit
# like its letters: the text origin is the cap top, the baseline 7 units down.
UPM, ASC, DESC = 1000, 1038, -222
UNIT = UPM / 9.4
BOX = 8 * UNIT                 # Phosphor's 256 box is 8 units square
MID = 3.5 * UNIT               # centred on the cap height (745 ~ 7 units)


def path_of(name, weight):
    src = (DEFS / f"{name}.es.js").read_text()
    block = re.search(r'"%s",(.*?)\n  \]' % weight, src, re.S).group(1)
    return re.findall(r'd: "([^"]+)"', block)


def main():
    names = re.findall(r"^    ([A-Z_]+)\(", ICONS.read_text(), re.M)
    missing = [n for n in names if n not in PHOSPHOR]
    if missing:
        sys.exit(f"no Phosphor icon for {missing}")
    order = [".notdef"] + [f"i{n.lower()}" for n in names]
    fb = FontBuilder(UPM, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({0xE000 + i: f"i{n.lower()}" for i, n in enumerate(names)})
    glyphs, metrics = {".notdef": TTGlyphPen(None).glyph()}, {".notdef": (int(BOX), 0)}
    k = BOX / 256
    for n in names:
        spec = PHOSPHOR[n]
        icon, weight = spec if isinstance(spec, tuple) else (spec, "bold")
        pen = TTGlyphPen(None)
        # SVG y runs down: flip it and centre the box on MID
        tp = TransformPen(Cu2QuPen(pen, 1.0, reverse_direction=True), (k, 0, 0, -k, 0, MID + BOX / 2))
        for d in path_of(icon, weight):
            parse_path(d, tp)
        glyphs[f"i{n.lower()}"] = pen.glyph()
        metrics[f"i{n.lower()}"] = (int(round(BOX)), 0)
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics({g: (w, glyphs[g].xMin if hasattr(glyphs[g], "xMin") else 0) for g, (w, _) in metrics.items()})
    fb.setupHorizontalHeader(ascent=ASC, descent=DESC)
    fb.setupNameTable({"familyName": "Dusk Icons", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=ASC, sTypoDescender=DESC, sTypoLineGap=0, usWinAscent=1296, usWinDescent=356, sCapHeight=745)
    fb.setupPost()
    fb.save(OUT)
    print(f"{OUT.relative_to(ROOT)}: {len(names)} icons")


main()
