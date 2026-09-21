#!/usr/bin/env python3
"""Import downloaded capes and Cosmetica accessories into the bundled registry.

    python3 tools/import_cosmetics.py ~/Downloads/minecraftcosmetics

Capes (any *.png / *.gif that is not an accessory texture):
  * static PNGs are stored as-is (RGBA) — the mod pads them to the next
    power-of-two multiple of 64x32 exactly like MinecraftCapes;
  * GIFs become a vertical strip of RGBA frames (height != width/2 is how
    the mod detects animation); the GIF delay is kept as ``frameMs`` unless
    it is a "browser default" <= 20 ms, in which case 100 ms is used;
  * animated capes are halved until the strip stays under ~8 MP and 16384 px
    tall so a 2048x1024 x 23-frame GIF does not become a 48 MP upload.

Accessories: a ``<name> - Cosmetica model.json`` + ``<name> - Cosmetica
texture.png`` pair. Attachment/offset/frames are looked up on the Cosmetica
search API by name (api.cloaks.gg); pass ``--offline`` to skip that and use
the defaults (``--attachment``, ``--offset``).

Ids continue after the highest id already in registry.json; entries whose
name already exists are replaced in place (same id) so re-running is safe.
"""
import argparse
import json
import os
import re
import sys
import urllib.request

from PIL import Image, ImageSequence

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "duskclient", "cosmetics")
REGISTRY = os.path.join(OUT, "registry.json")

MAX_ANIM_PIXELS = 8_000_000
MAX_STRIP_HEIGHT = 16384
DEFAULT_FRAME_MS = 100

ACCESSORY_RE = re.compile(r"^(?P<name>.+?) - Cosmetica (?P<kind>model|texture)\.(json|png)$", re.I)


def pretty_name(stem: str) -> str:
    stem = re.sub(r"\s+cape$", "", stem.strip(), flags=re.I)
    words = stem.split()
    return " ".join(w if any(c.isupper() for c in w[1:]) else w[:1].upper() + w[1:] for w in words)


def load_registry():
    with open(REGISTRY, encoding="utf-8") as f:
        reg = json.load(f)
    reg.setdefault("capes", [])
    reg.setdefault("accessories", [])
    return reg


def next_id(reg):
    ids = [e["id"] for e in reg["capes"]] + [e["id"] for e in reg["accessories"]]
    return (max(ids) if ids else 0) + 1


def upsert(entries, entry):
    for i, e in enumerate(entries):
        if e["name"].lower() == entry["name"].lower():
            entry["id"] = e["id"]
            entries[i] = entry
            return entry["id"], False
    entries.append(entry)
    return entry["id"], True


def gif_frames(path):
    img = Image.open(path)
    frames, delays = [], []
    for fr in ImageSequence.Iterator(img):
        frames.append(fr.convert("RGBA").copy())
        delays.append(fr.info.get("duration", 0))
    return frames, delays


def import_cape(path, reg, dry):
    stem = os.path.splitext(os.path.basename(path))[0]
    name = pretty_name(stem)
    img = Image.open(path)
    animated = getattr(img, "is_animated", False) and img.n_frames > 1
    entry = {"id": 0, "name": name, "glint": False, "upsideDown": False, "ears": False}

    if animated:
        frames, delays = gif_frames(path)
        w, h = frames[0].size
        # a real cape frame is 2:1; anything else would be mistaken for a strip
        if h != w // 2:
            print(f"  ! {name}: frames are {w}x{h}, not 2:1 — skipped")
            return None
        delay = max(delays) if delays else 0
        frame_ms = DEFAULT_FRAME_MS if delay <= 20 else delay
        n = len(frames)
        while w * h * n > MAX_ANIM_PIXELS or h * n > MAX_STRIP_HEIGHT:
            w, h = w // 2, h // 2
        if (w, h) != frames[0].size:
            print(f"  ~ {name}: {frames[0].size[0]}x{frames[0].size[1]} x{n} downscaled to {w}x{h}")
            frames = [f.resize((w, h), Image.LANCZOS) for f in frames]
        strip = Image.new("RGBA", (w, h * n), (0, 0, 0, 0))
        for i, f in enumerate(frames):
            strip.paste(f, (0, i * h))
        out_img = strip
        if frame_ms != DEFAULT_FRAME_MS:
            entry["frameMs"] = frame_ms
        desc = f"{n} frames @ {frame_ms} ms, {w}x{h}"
    else:
        out_img = img.convert("RGBA")
        w, h = out_img.size
        if h != w // 2:
            print(f"  ! {name}: {w}x{h} is not a 2:1 cape — skipped")
            return None
        desc = f"static {w}x{h}"

    cid = next_id(reg)
    entry["id"] = cid
    cid, created = upsert(reg["capes"], entry)
    print(f"  {'+' if created else '='} cape {cid:>3} {name}: {desc}")
    if not dry:
        d = os.path.join(OUT, "capes", str(cid))
        os.makedirs(d, exist_ok=True)
        out_img.save(os.path.join(d, "cape.png"), optimize=True)
    return cid


def cosmetica_lookup(name):
    body = json.dumps({"query": name, "page": 1, "pageSize": 10, "includeUnrendered": True}).encode()
    req = urllib.request.Request(
        "https://api.cloaks.gg/search/cosmetics", data=body,
        headers={"Content-Type": "application/json", "User-Agent": "dusklauncher-import"})
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.load(r)
    for res in data.get("results", []):
        acc = res.get("accessory")
        if acc and acc.get("name", "").strip().lower() == name.strip().lower():
            return acc
    return None


def import_accessory(name, model_path, tex_path, reg, args, dry):
    with open(model_path, encoding="utf-8") as f:
        model = json.load(f)
    tex = Image.open(tex_path).convert("RGBA")
    meta = None
    if not args.offline:
        try:
            meta = cosmetica_lookup(name)
        except Exception as e:  # network is best-effort
            print(f"  ! Cosmetica lookup failed for {name!r}: {e}")
    if meta:
        offset = meta.get("offset") or [0, 0, 0]
        entry = {
            "id": 0,
            "name": pretty_name(meta.get("name") or name),
            "attachment": meta.get("attachment") or args.attachment,
            "offset": [float(v) for v in offset[:3]],
            "mirrored": bool(meta.get("mirrored")),
            "frames": int(meta.get("frames") or 1),
            "ticksPerFrame": int(meta.get("ticksPerFrame") or 1),
            "flags": int(meta.get("flags") or 0),
            "source": f"cosmetica:{meta.get('id')}",
        }
        src = "cosmetica"
    else:
        entry = {
            "id": 0,
            "name": pretty_name(name),
            "attachment": args.attachment,
            "offset": [float(v) for v in args.offset],
            "mirrored": False,
            "frames": 1,
            "ticksPerFrame": 1,
            "flags": 0,
        }
        src = "defaults"
    if model.get("usesUVRotations"):
        entry["usesUvRotations"] = True

    entry["id"] = next_id(reg)
    aid, created = upsert(reg["accessories"], entry)
    print(f"  {'+' if created else '='} accessory {aid:>3} {entry['name']}: {entry['attachment']} "
          f"offset={entry['offset']} frames={entry['frames']} ({src}), texture {tex.size[0]}x{tex.size[1]}")
    if not dry:
        d = os.path.join(OUT, "accessories", str(aid))
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "model.json"), "w", encoding="utf-8") as f:
            json.dump(model, f, separators=(",", ":"))
        tex.save(os.path.join(d, "texture.png"), optimize=True)
    return aid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--offline", action="store_true", help="don't ask api.cloaks.gg for accessory placement")
    ap.add_argument("--attachment", default="body", help="fallback accessory attachment")
    ap.add_argument("--offset", nargs=3, type=float, default=[0, 0, 0], help="fallback accessory offset (px)")
    args = ap.parse_args()

    reg = load_registry()
    files = sorted(os.listdir(args.source))
    accessories = {}
    capes = []
    for f in files:
        m = ACCESSORY_RE.match(f)
        if m:
            accessories.setdefault(m.group("name"), {})[m.group("kind").lower()] = os.path.join(args.source, f)
        elif f.lower().endswith((".png", ".gif")):
            capes.append(os.path.join(args.source, f))

    # drop byte-identical duplicates (the same cape saved twice under two names)
    seen = {}
    for p in list(capes):
        key = Image.open(p).convert("RGBA").tobytes()
        h = hash(key)
        if h in seen:
            print(f"  - {os.path.basename(p)} is identical to {os.path.basename(seen[h])} — skipped")
            capes.remove(p)
        else:
            seen[h] = p

    print("capes:")
    for p in capes:
        import_cape(p, reg, args.dry_run)
    print("accessories:")
    for name, parts in accessories.items():
        if "model" not in parts or "texture" not in parts:
            print(f"  ! {name}: needs both a model.json and a texture.png — skipped")
            continue
        import_accessory(name, parts["model"], parts["texture"], reg, args, args.dry_run)

    if not args.dry_run:
        with open(REGISTRY, "w", encoding="utf-8") as f:
            json.dump(reg, f, indent=2)
            f.write("\n")
        print(f"wrote {os.path.relpath(REGISTRY)}")


if __name__ == "__main__":
    sys.exit(main())
