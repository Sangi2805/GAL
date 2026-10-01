"""
Draws Sidekick, GAL's pink elephant, for everything that is a picture rather than live code:

  res/drawable/ic_launcher_foreground.xml, ic_launcher_background.xml, ic_launcher_monochrome.xml
  res/drawable/splash_icon.xml
  res/drawable/mascot_smug.xml, mascot_bored.xml, mascot_disappointed.xml, mascot_horrified.xml (roast cards)
  res/drawable/ic_stat_sidekick.xml (notification icon)
  tools/mascot/preview/*.svg and *.png (the same shapes, for checking by eye; PNGs need cairosvg)

Every picture is built from one list of shapes, written both as SVG and as an Android VectorDrawable, so the
preview is what ships. The live elephant on screen is drawn in code (sidekick/BlobPainter.kt) with the same
colours and proportions; keep the two in step.

The design is our own: a pink elephant with a bow, lashes, rosy cheeks and a bendy trunk that does the work
(boops and smacks app crates open). No hammer, no circus hat, nothing borrowed.

Run from the GAL folder:  python tools/mascot/generate_elephant.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAWABLES = ROOT / "app/src/main/res/drawable"
PREVIEW = Path(__file__).resolve().parent / "preview"

SKIN_LIGHT = "#FFD0E4"
SKIN_MID = "#F8B2D1"
SKIN_DARK = "#EE8DB9"
INNER_EAR = "#FF9EC4"
RIM = "#9C3D6E"
INK = "#3A1530"
WHITE = "#FFFFFF"
CHEEK = "#FF6F9F"
BOW = "#FF5C8A"
BOW_KNOT = "#FF7FA3"
SWEAT = "#7EC8F0"
TONGUE = "#FF7A8A"
BACKGROUND = "#BDF2D5"
WOOD = "#E0A158"
WOOD_DARK = "#7A4718"
CRACK = "#5A3210"
APP_COLOURS = ["#FF6B5B", "#4C8DFF", "#FFC23D", "#1FA463"]


# ---- Shapes -------------------------------------------------------------------------------------------------

def ellipse(cx, cy, rx, ry):
    return (f"M{cx - rx:.2f},{cy:.2f} a{rx:.2f},{ry:.2f} 0 1,0 {2 * rx:.2f},0 "
            f"a{rx:.2f},{ry:.2f} 0 1,0 {-2 * rx:.2f},0 Z")


def rrect(x, y, w, h, r):
    return (f"M{x + r:.2f},{y:.2f} h{w - 2 * r:.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f} v{h - 2 * r:.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f} h{-(w - 2 * r):.2f} a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f} "
            f"v{-(h - 2 * r):.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f} Z")


def shape(d, fill=None, stroke=None, width=0, alpha=1.0, gradient=None, cap="round", join="round"):
    return {"d": d, "fill": fill, "stroke": stroke, "width": width, "alpha": alpha, "gradient": gradient,
            "cap": cap, "join": join}


def group(children, rotate=0, px=0, py=0, scale=1.0, tx=0, ty=0):
    return {"group": children, "rotate": rotate, "px": px, "py": py, "scale": scale, "tx": tx, "ty": ty}


SKIN = ("vertical", SKIN_LIGHT, SKIN_DARK)


# ---- The elephant, in a 512 box (the logo's coordinates) ----------------------------------------------------

def ears(mono=False):
    out = []
    for left in (True, False):
        if left:
            d = "M140 170 C50 140 20 250 60 310 C90 356 150 350 170 320 Z"
            inner = "M130 198 C78 184 62 254 86 292 C104 320 138 316 150 300 Z"
        else:
            d = "M300 170 C390 140 420 250 380 310 C350 356 290 350 270 320 Z"
            inner = "M310 198 C362 184 378 254 354 292 C336 320 302 316 290 300 Z"
        if mono:
            out.append(shape(d, fill=WHITE))
        else:
            out.append(shape(d, gradient=SKIN, stroke=RIM, width=10))
            out.append(shape(inner, fill=INNER_EAR))
    return out


def head(mono=False):
    d = ellipse(220, 250, 108, 102)
    if mono:
        return [shape(d, fill=WHITE)]
    return [shape(d, gradient=SKIN, stroke=RIM, width=10),
            group([shape(ellipse(172, 186, 28, 13), fill=WHITE, alpha=0.55)], rotate=-25, px=172, py=186)]


TRUNK_SMACK = "M220 286 C218 380 270 430 330 400 C350 390 360 366 366 336"
TRUNK_REST = "M220 280 C214 330 236 372 282 370 C306 369 318 352 312 334"


def trunk(path, mono=False):
    if mono:
        return [shape(path, stroke=WHITE, width=56)]
    return [shape(path, stroke=RIM, width=56),
            shape(path, stroke=SKIN_MID, width=38),
            shape("M206 330 q14 6 28 0 M214 352 q12 6 24 0", stroke=RIM, width=5, alpha=0.45)]


def bow():
    return [group([
        shape("M0 0 L-42 -25 Q-56 0 -42 25 Z", fill=BOW, stroke=RIM, width=7),
        shape("M0 0 L42 -25 Q56 0 42 25 Z", fill=BOW, stroke=RIM, width=7),
        shape(ellipse(0, 0, 12, 12), fill=BOW_KNOT, stroke=RIM, width=7),
    ], rotate=-18, px=0, py=0, tx=142, ty=152)]


def eyes(mood):
    out = []
    wide = mood == "horrified"
    for ex in (180, 262):
        if wide:
            out.append(shape(ellipse(ex, 236, 28, 32), fill=WHITE, stroke=RIM, width=4))
            out.append(shape(ellipse(ex + 2, 238, 11, 14), fill=INK))
            out.append(shape(ellipse(ex + 6, 232, 4, 4), fill=WHITE))
        else:
            out.append(shape(ellipse(ex, 236, 19, 24), fill=INK))
            out.append(shape(ellipse(ex + 7, 226, 7, 7), fill=WHITE))
        # Lashes on the outer side.
        side = -1 if ex < 220 else 1
        lx = ex + side * 15
        out.append(shape(f"M{lx},{222} l{side * 14},-9 M{lx - side * 3},{213} l{side * 9},-13", stroke=INK, width=6))
        lid = {"smug": 0.42, "bored": 0.58, "disappointed": 0.26}.get(mood, 0)
        if lid:
            top = 236 - 24
            edge = top + 48 * lid
            out.append(shape(f"M{ex - 22},{top - 8} H{ex + 22} V{edge} H{ex - 22} Z", fill=SKIN_LIGHT))
            out.append(shape(f"M{ex - 18},{edge} H{ex + 18}", stroke=INK, width=5))
    return out


def brows(mood):
    # Inner end, outer end and arch offsets per eye (positive is down), as in BlobPainter.drawBrows.
    sets = {
        "smug": [(2, 2, -2), (-8, -4, -12)],
        "bored": [(7, 7, 0), (7, 7, 0)],
        "disappointed": [(-10, 6, 0), (-10, 6, 0)],
        "horrified": [(-14, -8, -12), (-14, -8, -12)],
        "cheeky": [(0, 0, -14), (6, 4, 0)],
    }
    if mood not in sets:
        return []
    out = []
    base = 198
    for ex, (inner, outer, arch) in zip((180, 262), sets[mood]):
        side = -1 if ex < 220 else 1
        ix, ox = ex - side * 20, ex + side * 20
        midy = base + (inner + outer) / 2 + arch
        out.append(shape(f"M{ix},{base + inner} Q{ex},{midy} {ox},{base + outer}", stroke=INK, width=8))
    return out


def cheeks():
    return [shape(ellipse(146, 282, 19, 11), fill=CHEEK, alpha=0.6), shape(ellipse(296, 282, 19, 11), fill=CHEEK, alpha=0.6)]


def mouth(mood):
    mx, my = 172, 308
    if mood == "smug":
        return [shape(f"M{mx - 14},{my} Q{mx},{my + 10} {mx + 16},{my - 6}", stroke=INK, width=7)]
    if mood == "bored":
        return [shape(f"M{mx - 12},{my + 2} H{mx + 12}", stroke=INK, width=7)]
    if mood == "disappointed":
        return [shape(f"M{mx - 13},{my + 8} Q{mx},{my - 4} {mx + 13},{my + 8}", stroke=INK, width=7)]
    if mood == "horrified":
        return [shape(ellipse(mx, my + 4, 10, 14), fill=INK), shape(ellipse(mx, my + 10, 6, 5), fill=TONGUE)]
    return [shape(f"M{mx - 12},{my - 4} Q{mx + 2},{my + 8} {mx + 18},{my - 2}", stroke=INK, width=7)]


def sweat():
    return [shape("M318 140 Q332 160 318 172 Q304 160 318 140 Z", fill=SWEAT)]


def crate():
    parts = [
        shape(rrect(362, 276, 116, 116, 18), fill=WOOD, stroke=WOOD_DARK, width=9),
        shape("M362 314 H478 M362 354 H478", stroke=WOOD_DARK, width=6, alpha=0.5, cap="butt"),
        shape(rrect(394, 306, 54, 54, 14), fill=WHITE, stroke=WOOD_DARK, width=5),
    ]
    for i, c in enumerate(APP_COLOURS):
        x = 405 + (i % 2) * 19
        y = 317 + (i // 2) * 19
        parts.append(shape(rrect(x, y, 13, 13, 3), fill=c))
    parts.append(shape("M366 330 l14 6 -6 12 14 8", stroke=CRACK, width=5))
    return [group(parts, rotate=8, px=410, py=340)]


def smack_lines():
    return [shape("M352 300 l8 -20 M346 340 l-22 4 M360 368 l-14 18", stroke="#FF9F1C", width=9)]


def elephant(mood="cheeky", trunk_path=TRUNK_REST, with_crate=False):
    out = ears() + head() + cheeks()
    if with_crate:
        out += crate() + smack_lines()
    out += trunk(trunk_path) + bow() + eyes(mood) + brows(mood) + mouth(mood)
    if mood == "horrified":
        out += sweat()
    return out


def silhouette(trunk_path=TRUNK_SMACK):
    """One colour, for the themed icon and the notification: ears, head and trunk, eyes cut out."""
    return ears(mono=True) + head(mono=True) + trunk(trunk_path, mono=True)


# ---- Writers --------------------------------------------------------------------------------------------------

def _gradient_svg(g, gid, bounds):
    _, c0, c1 = g
    return (f'<linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{c0}"/><stop offset="1" stop-color="{c1}"/></linearGradient>')


def to_svg(items, size, background=None, clip_radius=None):
    defs, body = [], []
    counter = [0]

    def emit(item, indent):
        if "group" in item:
            t = f'translate({item["tx"]} {item["ty"]}) rotate({item["rotate"]} {item["px"]} {item["py"]}) scale({item["scale"]})'
            body.append(f'{indent}<g transform="{t}">')
            for child in item["group"]:
                emit(child, indent + "  ")
            body.append(f"{indent}</g>")
            return
        attrs = [f'd="{item["d"]}"']
        if item["gradient"]:
            counter[0] += 1
            gid = f"g{counter[0]}"
            defs.append(_gradient_svg(item["gradient"], gid, None))
            attrs.append(f'fill="url(#{gid})"')
        else:
            attrs.append(f'fill="{item["fill"] or "none"}"')
        if item["stroke"]:
            attrs.append(f'stroke="{item["stroke"]}" stroke-width="{item["width"]}" '
                         f'stroke-linecap="{item["cap"]}" stroke-linejoin="{item["join"]}"')
        if item["alpha"] != 1.0:
            attrs.append(f'opacity="{item["alpha"]}"')
        body.append(f'{indent}<path {" ".join(attrs)}/>')

    for it in items:
        emit(it, "  ")
    clip = ""
    open_g = close_g = ""
    if clip_radius is not None:
        defs.append(f'<clipPath id="tile"><rect width="{size}" height="{size}" rx="{clip_radius}"/></clipPath>')
        open_g, close_g = '<g clip-path="url(#tile)">', "</g>"
    bg = f'<rect width="{size}" height="{size}" fill="{background}"/>' if background else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">'
            f'<defs>{"".join(defs)}</defs>{open_g}{bg}\n' + "\n".join(body) + f"\n{close_g}</svg>\n")


def _hex_vd(colour, alpha=1.0):
    a = round(alpha * 255)
    return f"#{a:02X}{colour.lstrip('#').upper()}"


def to_vector(items, size, viewport, comment, tint_white=None):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {comment} Generated by tools/mascot/generate_elephant.py. -->",
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
             'xmlns:aapt="http://schemas.android.com/aapt"',
             f'    android:width="{size}dp"', f'    android:height="{size}dp"',
             f'    android:viewportWidth="{viewport}"', f'    android:viewportHeight="{viewport}">']

    def emit(item, indent, bounds_y):
        if "group" in item:
            lines.append(f'{indent}<group android:translateX="{item["tx"]}" android:translateY="{item["ty"]}">')
            lines.append(f'{indent}    <group android:rotation="{item["rotate"]}" android:pivotX="{item["px"]}" '
                         f'android:pivotY="{item["py"]}" android:scaleX="{item["scale"]}" android:scaleY="{item["scale"]}">')
            for child in item["group"]:
                emit(child, indent + "        ", bounds_y)
            lines.append(f"{indent}    </group>")
            lines.append(f"{indent}</group>")
            return
        attrs = [f'android:pathData="{item["d"]}"']
        if item["fill"] and not item["gradient"]:
            attrs.append(f'android:fillColor="{_hex_vd(item["fill"])}"')
            if item["alpha"] != 1.0:
                attrs.append(f'android:fillAlpha="{item["alpha"]}"')
        if item["stroke"]:
            attrs.append(f'android:strokeColor="{_hex_vd(item["stroke"])}"')
            attrs.append(f'android:strokeWidth="{item["width"]}"')
            attrs.append(f'android:strokeLineCap="{item["cap"]}"')
            attrs.append(f'android:strokeLineJoin="{item["join"]}"')
            if item["alpha"] != 1.0:
                attrs.append(f'android:strokeAlpha="{item["alpha"]}"')
        if item["gradient"]:
            _, c0, c1 = item["gradient"]
            y0, y1 = bounds_y
            lines.append(f"{indent}<path " + " ".join(attrs) + ">")
            lines.append(f'{indent}    <aapt:attr name="android:fillColor">')
            lines.append(f'{indent}        <gradient android:type="linear" android:startX="0" android:startY="{y0}" '
                         f'android:endX="0" android:endY="{y1}" android:startColor="{_hex_vd(c0)}" android:endColor="{_hex_vd(c1)}"/>')
            lines.append(f"{indent}    </aapt:attr>")
            lines.append(f"{indent}</path>")
        else:
            lines.append(f"{indent}<path " + " ".join(attrs) + " />")

    for it in items:
        emit(it, "    ", (140, 360))
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def fit(items, src_box, dst_size, dst_box):
    """Scales the 512-box drawing so [src_box] (x0, y0, x1, y1) fills [dst_box] in a [dst_size] canvas."""
    x0, y0, x1, y1 = src_box
    dx0, dy0, dx1, dy1 = dst_box
    s = min((dx1 - dx0) / (x1 - x0), (dy1 - dy0) / (y1 - y0))
    tx = dx0 + ((dx1 - dx0) - (x1 - x0) * s) / 2 - x0 * s
    ty = dy0 + ((dy1 - dy0) - (y1 - y0) * s) / 2 - y0 * s
    return [group(items, scale=round(s, 5), tx=round(tx, 3), ty=round(ty, 3))]


def write(name, text):
    (DRAWABLES / name).write_text(text, encoding="utf-8")
    print("wrote", (DRAWABLES / name).relative_to(ROOT))


def preview(name, svg):
    PREVIEW.mkdir(exist_ok=True)
    (PREVIEW / f"{name}.svg").write_text(svg, encoding="utf-8")
    try:
        import cairosvg
        cairosvg.svg2png(bytestring=svg.encode(), write_to=str(PREVIEW / f"{name}.png"), output_width=512)
    except ImportError:
        pass


def main():
    logo = elephant("cheeky", TRUNK_SMACK, with_crate=True)
    LOGO_BOX = (24, 110, 486, 456)

    # Store icon and the full logo preview.
    preview("logo", to_svg(logo, 512, BACKGROUND, clip_radius=112))

    # Adaptive icon: everything inside the 66dp safe zone of the 108dp canvas.
    fg = fit(logo, LOGO_BOX, 108, (19, 19, 89, 89))
    write("ic_launcher_foreground.xml", to_vector(fg, 108, 108, "Adaptive icon foreground: Sidekick smacking an app crate, inside the 66dp safe zone."))
    write("ic_launcher_background.xml", to_vector([shape("M0,0 H108 V108 H0 Z", fill=BACKGROUND)], 108, 108, "Adaptive icon background layer."))
    mono = fit(silhouette(), (24, 110, 420, 456), 108, (24, 24, 84, 84))
    write("ic_launcher_monochrome.xml", to_vector(mono, 108, 108, "Themed icon: one-colour silhouette."))
    preview("icon_foreground", to_svg(fg, 108, BACKGROUND))

    # Splash: the art fills the middle 192 of a 288 canvas, as LandingScreen expects.
    splash = fit(logo, LOGO_BOX, 288, (48, 48, 240, 240))
    write("splash_icon.xml", to_vector(splash, 288, 288, "Android 12+ splash icon: art in the middle 192 of 288."))

    # Roast card faces: head, ears, resting trunk, one mood each.
    for mood in ("smug", "bored", "disappointed", "horrified"):
        face = fit(elephant(mood, TRUNK_REST), (20, 100, 420, 420), 120, (4, 4, 116, 116))
        write(f"mascot_{mood}.xml", to_vector(face, 120, 120, f"Roast card face: {mood}."))
        preview(f"mascot_{mood}", to_svg(face, 120, "#FFF7FB"))

    # Notification icon: white silhouette, 24dp.
    stat = fit(silhouette(), (24, 110, 420, 456), 24, (2, 2, 22, 22))
    write("ic_stat_sidekick.xml", to_vector(stat, 24, 24, "Notification icon: Sidekick's silhouette."))


if __name__ == "__main__":
    main()
