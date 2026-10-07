"""
Draws GAL's pink elephant, for everything that is a picture rather than live code:

  res/drawable/ic_launcher_foreground.xml, ic_launcher_background.xml, ic_launcher_monochrome.xml
  res/drawable/splash_icon.xml
  res/drawable/mascot_smug.xml, mascot_bored.xml, mascot_disappointed.xml, mascot_horrified.xml (roast cards, whole elephant)
  res/drawable/ic_stat_sidekick.xml (notification icon)
  tools/mascot/preview/*.svg and *.png (the same shapes, for checking by eye; PNGs need cairosvg)
  tools/mascot/preview/logo.png (512 store icon) and cover.png (1024 x 500 cover picture)

Every picture is built from one list of shapes, written both as SVG and as an Android VectorDrawable, so the
preview is what ships. The live elephant on screen is drawn in code (sidekick/BlobPainter.kt) with the same
colours and proportions; keep the two in step.

The design is our own: a chubby, full-body pink cartoon elephant with a bow, lashes, rosy cheeks, toenails, a
little tail and a bendy trunk. No hammer, no circus hat, nothing borrowed.

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
#
# A full-body cartoon elephant standing side on and facing right, head turned towards us. The numbers follow
# BlobPainter.draw with u = 400 and the ground at y = 456, so the pictures and the live elephant match.

GROUND = 456
BODY_X, BODY_Y, BODY_RX, BODY_RY = 220, 312, 124, 90
HIP_Y = BODY_Y + 27
HX, HY, HR = 324, 240, 80
LEG_W = 52
SKIN_FAR = "#E58AB4"
NAIL = "#FFF3F8"
COLOR_ANGER = "#FF5A6E"
SPARKLE = "#FFC83D"


def leg(x, far=False, swing=0, mono=False):
    top = HIP_Y - LEG_W * 0.6
    d = rrect(x - LEG_W / 2, top, LEG_W, GROUND - top, LEG_W * 0.45)
    if mono:
        parts = [shape(d, fill=WHITE)]
    else:
        parts = [shape(d, fill=SKIN_FAR if far else None, gradient=None if far else SKIN, stroke=RIM, width=7)]
        if not far:
            for k in range(3):
                nx = x - LEG_W * 0.28 + k * LEG_W * 0.28
                parts.append(shape(ellipse(nx, GROUND - LEG_W * 0.14, LEG_W * 0.11, LEG_W * 0.10), fill=NAIL))
    return [group(parts, rotate=swing, px=x, py=HIP_Y)] if swing else parts


def tail(mono=False):
    x, y = BODY_X - BODY_RX * 0.97, BODY_Y - BODY_RY * 0.15
    d = f"M{x:.0f} {y:.0f} Q{x - 28:.0f} {y + 12:.0f} {x - 24:.0f} {y + 52:.0f}"
    if mono:
        return [shape(d, stroke=WHITE, width=12)]
    return [shape(d, stroke=RIM, width=7), shape(ellipse(x - 24, y + 58, 10, 10), fill=BOW_KNOT)]


def body(mono=False):
    d = ellipse(BODY_X, BODY_Y, BODY_RX, BODY_RY)
    if mono:
        return [shape(d, fill=WHITE)]
    return [shape(d, gradient=SKIN, stroke=RIM, width=7),
            group([shape(ellipse(BODY_X - 37, BODY_Y - 62, 31, 12), fill=WHITE, alpha=0.5)],
                  rotate=-12, px=BODY_X - 37, py=BODY_Y - 62)]


def ear(mono=False):
    x, y = HX - HR * 0.70, HY + HR * 0.10
    outer = ellipse(x - HR * 0.20, y + HR * 0.075, HR * 0.70, HR * 0.875)
    inner = ellipse(x - HR * 0.225, y + HR * 0.085, HR * 0.475, HR * 0.665)
    if mono:
        return [shape(outer, fill=WHITE)]
    return [group([shape(outer, gradient=SKIN, stroke=RIM, width=7), shape(inner, fill=INNER_EAR)],
                  rotate=-12, px=x + HR * 0.4, py=y - HR * 0.3)]


def head(mood="cheeky", mono=False):
    d = ellipse(HX, HY, HR, HR * 0.95)
    if mono:
        return [shape(d, fill=WHITE)]
    out = [shape(d, gradient=SKIN, stroke=RIM, width=7)]
    if mood == "angry":
        out.append(shape(d, fill=COLOR_ANGER, alpha=0.43))
    out.append(shape(ellipse(HX - HR * 0.33, HY - HR * 0.64, HR * 0.27, HR * 0.14), fill=WHITE, alpha=0.5))
    return out


# Trunk paths start at the front of the face (HX + 0.55 HR, HY + 0.3 HR).
TRUNK_REST = "M368 264 C380 300 384 330 394 352 C399 364 390 372 381 365"
TRUNK_UP = "M368 264 C404 276 424 246 428 214 C430 200 418 194 410 202"


def trunk(path, mono=False):
    if mono:
        return [shape(path, stroke=WHITE, width=42)]
    return [shape(path, stroke=RIM, width=42), shape(path, stroke=SKIN_MID, width=30)]


def bow(mono=False):
    if mono:
        return []
    return [group([
        shape("M0 0 L-32 -19 Q-42 0 -32 19 Z", fill=BOW, stroke=RIM, width=6),
        shape("M0 0 L32 -19 Q42 0 32 19 Z", fill=BOW, stroke=RIM, width=6),
        shape(ellipse(0, 0, 9, 9), fill=BOW_KNOT, stroke=RIM, width=6),
    ], rotate=-18, px=0, py=0, tx=HX - HR * 0.35, ty=HY - HR * 0.88)]


EYE_X = (HX - HR * 0.30, HX + HR * 0.22)
EYE_Y = HY - HR * 0.08


def eyes(mood):
    out = []
    wide = mood == "horrified"
    rx, ry = HR * 0.16, HR * 0.21
    for i, ex in enumerate(EYE_X):
        ey = EYE_Y
        if wide:
            out.append(shape(ellipse(ex, ey, rx * 1.4, ry * 1.35), fill=WHITE, stroke=RIM, width=3))
            out.append(shape(ellipse(ex, ey, rx * 0.6, ry * 0.6), fill=INK))
        else:
            out.append(shape(ellipse(ex, ey, rx, ry), fill=INK))
            out.append(shape(ellipse(ex + rx * 0.35, ey - ry * 0.35, rx * 0.35, rx * 0.35), fill=WHITE))
        side = -1 if i == 0 else 1
        lx, ly = ex + side * rx * 0.8, ey - ry * 0.55
        out.append(shape(f"M{lx:.1f},{ly:.1f} l{side * rx * 0.7:.1f},{-rx * 0.4:.1f} "
                         f"M{lx - side * rx * 0.2:.1f},{ly - ry * 0.3:.1f} l{side * rx * 0.55:.1f},{-rx * 0.7:.1f}",
                         stroke=INK, width=4))
        lid = {"smug": 0.42, "bored": 0.58, "disappointed": 0.26, "angry": 0.30}.get(mood, 0)
        if lid:
            top = ey - ry * 1.1
            edge = ey - ry + 2 * ry * lid
            out.append(shape(f"M{ex - rx * 1.2:.1f},{top:.1f} H{ex + rx * 1.2:.1f} V{edge:.1f} H{ex - rx * 1.2:.1f} Z",
                             fill=SKIN_LIGHT if mood != "angry" else "#FFB3C2"))
            out.append(shape(f"M{ex - rx * 1.05:.1f},{edge:.1f} H{ex + rx * 1.05:.1f}", stroke=INK, width=4))
    return out


def brows(mood):
    # Inner end, outer end and arch, in head radii (positive is down), as in BlobPainter.drawBrows.
    sets = {
        "smug": [(0.02, 0.02, 0), (-0.08, -0.04, -0.1)],
        "bored": [(0.06, 0.06, 0), (0.06, 0.06, 0)],
        "disappointed": [(-0.09, 0.05, 0), (-0.09, 0.05, 0)],
        "horrified": [(-0.13, -0.07, -0.1), (-0.13, -0.07, -0.1)],
        "angry": [(0.12, -0.08, 0), (0.12, -0.08, 0)],
        "proud": [(-0.04, -0.04, -0.08), (-0.04, -0.04, -0.08)],
    }
    if mood not in sets:
        return []
    out = []
    base = HY - HR * 0.42
    for i, (ex, (inner, outer, arch)) in enumerate(zip(EYE_X, sets[mood])):
        side = -1 if i == 0 else 1
        ix, ox = ex - side * HR * 0.16, ex + side * HR * 0.16
        midy = base + (inner + outer) / 2 * HR + arch * HR
        out.append(shape(f"M{ix:.1f},{base + inner * HR:.1f} Q{ex:.1f},{midy:.1f} {ox:.1f},{base + outer * HR:.1f}",
                         stroke=INK, width=6))
    return out


def cheek(mood):
    colour = COLOR_ANGER if mood == "angry" else CHEEK
    return [shape(ellipse(HX - HR * 0.15, HY + HR * 0.32, HR * 0.2, HR * 0.1), fill=colour, alpha=0.7)]


def mouth(mood):
    mx, my, r = HX - HR * 0.08, HY + HR * 0.62, HR
    if mood == "smug":
        return [shape(f"M{mx - r * .12:.1f},{my:.1f} Q{mx:.1f},{my + r * .08:.1f} {mx + r * .14:.1f},{my - r * .05:.1f}", stroke=INK, width=5)]
    if mood == "bored":
        return [shape(f"M{mx - r * .1:.1f},{my + 2:.1f} H{mx + r * .1:.1f}", stroke=INK, width=5)]
    if mood in ("disappointed", "angry"):
        return [shape(f"M{mx - r * .12:.1f},{my + r * .07:.1f} Q{mx:.1f},{my - r * .05:.1f} {mx + r * .12:.1f},{my + r * .07:.1f}", stroke=INK, width=5)]
    if mood == "horrified":
        return [shape(ellipse(mx, my + r * .05, r * .08, r * .11), fill=INK), shape(ellipse(mx, my + r * .1, r * .05, r * .04), fill=TONGUE)]
    if mood == "proud":
        return [shape(f"M{mx - r * .15:.1f},{my - 2:.1f} Q{mx:.1f},{my + r * .24:.1f} {mx + r * .15:.1f},{my - 2:.1f} Z", fill=INK),
                shape(ellipse(mx, my + r * .1, r * .06, r * .03), fill=TONGUE)]
    return [shape(f"M{mx - r * .11:.1f},{my - 2:.1f} Q{mx:.1f},{my + r * .09:.1f} {mx + r * .13:.1f},{my - 4:.1f}", stroke=INK, width=5)]


def sweat():
    return [shape(f"M{HX + 60} {HY - 72} q11 15 0 25 q-11 -10 0 -25 Z", fill=SWEAT)]


def steam():
    return [shape(ellipse(HX - 40, HY - 104, 13, 13), fill="#C9C9D3"), shape(ellipse(HX - 30, HY - 102, 10, 10), fill="#C9C9D3"),
            shape(ellipse(HX + 32, HY - 112, 16, 16), fill="#C9C9D3"), shape(ellipse(HX + 45, HY - 110, 12, 12), fill="#C9C9D3")]


def sparkle(x, y, s):
    return shape(f"M{x},{y - 2 * s} L{x + s * .5},{y - s * .5} L{x + 2 * s},{y} L{x + s * .5},{y + s * .5} "
                 f"L{x},{y + 2 * s} L{x - s * .5},{y + s * .5} L{x - 2 * s},{y} L{x - s * .5},{y - s * .5} Z", fill=SPARKLE)


def elephant(mood="cheeky", trunk_path=TRUNK_REST, walking=False, extras=True):
    """The whole elephant. [walking] puts her mid-stride, which reads better on an icon than four straight legs."""
    sw = (14, -12, -10, 12) if walking else (0, 0, 0, 0)
    out = leg(BODY_X - BODY_RX * 0.48, far=True, swing=sw[0]) + leg(BODY_X + BODY_RX * 0.60, far=True, swing=sw[1])
    out += tail() + body()
    out += leg(BODY_X - BODY_RX * 0.68, swing=sw[2]) + leg(BODY_X + BODY_RX * 0.38, swing=sw[3])
    out += ear() + head(mood) + cheek(mood) + eyes(mood) + brows(mood) + mouth(mood) + trunk(trunk_path) + bow()
    if extras:
        if mood == "horrified":
            out += sweat()
        if mood == "angry":
            out += steam()
        if mood == "proud":
            out += [sparkle(70, 230, 14), sparkle(440, 150, 11), sparkle(130, 140, 9)]
    return out


def silhouette(trunk_path=TRUNK_UP):
    """One colour, for the themed icon and the notification: the whole elephant, no face."""
    return (leg(BODY_X - BODY_RX * 0.48, mono=True) + leg(BODY_X + BODY_RX * 0.60, mono=True) + tail(mono=True)
            + body(mono=True) + leg(BODY_X - BODY_RX * 0.68, mono=True) + leg(BODY_X + BODY_RX * 0.38, mono=True)
            + ear(mono=True) + head(mono=True) + trunk(trunk_path, mono=True))


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
        emit(it, "    ", (150, 460))
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
    logo = elephant("proud", TRUNK_UP, walking=True, extras=False) + [sparkle(452, 130, 12), sparkle(70, 170, 9)]
    LOGO_BOX = (40, 120, 470, 470)

    # Store icon and the full logo preview.
    preview("logo", to_svg(logo, 512, BACKGROUND, clip_radius=112))

    # Adaptive icon: everything inside the 66dp safe zone of the 108dp canvas.
    fg = fit(logo, LOGO_BOX, 108, (20, 20, 88, 88))
    write("ic_launcher_foreground.xml", to_vector(fg, 108, 108, "Adaptive icon foreground: the pink elephant mid-stride, trunk up, inside the 66dp safe zone."))
    write("ic_launcher_background.xml", to_vector([shape("M0,0 H108 V108 H0 Z", fill=BACKGROUND)], 108, 108, "Adaptive icon background layer."))
    SIL_BOX = (60, 150, 450, 462)
    mono = fit(silhouette(), SIL_BOX, 108, (26, 26, 82, 82))
    write("ic_launcher_monochrome.xml", to_vector(mono, 108, 108, "Themed icon: one-colour silhouette."))
    preview("icon_foreground", to_svg(fg, 108, BACKGROUND))
    preview("icon_monochrome", to_svg(mono, 108, "#333333"))

    # Splash: the art fills the middle 192 of a 288 canvas, as LandingScreen expects.
    splash = fit(logo, LOGO_BOX, 288, (48, 48, 240, 240))
    write("splash_icon.xml", to_vector(splash, 288, 288, "Android 12+ splash icon: art in the middle 192 of 288."))

    # Roast card faces: the whole elephant, one mood each.
    for mood in ("smug", "bored", "disappointed", "horrified"):
        face = fit(elephant(mood, TRUNK_REST), (50, 130, 460, 466), 120, (4, 4, 116, 116))
        write(f"mascot_{mood}.xml", to_vector(face, 120, 120, f"Roast card elephant: {mood}."))
        preview(f"mascot_{mood}", to_svg(face, 120, "#FFF7FB"))

    # Cover picture (Play Store feature graphic, 1024 x 500): the elephant walking, saying no to a phone.
    cover_art = fit(elephant("disappointed", TRUNK_UP, walking=True), (40, 120, 470, 470), 1024, (650, 60, 1010, 470))
    phone = [shape(rrect(70, 120, 150, 270, 26), fill="#2B2B3A"), shape(rrect(82, 140, 126, 230, 14), fill="#FFFFFF")]
    for i, c in enumerate(APP_COLOURS):
        phone.append(shape(rrect(98 + (i % 2) * 52, 162 + (i // 2) * 52, 40, 40, 10), fill=c))
    phone.append(shape("M60 110 L230 400 M230 110 L60 400", stroke="#FF5C8A", width=16))
    words = ('<text x="270" y="200" font-family="sans-serif" font-size="96" font-weight="bold" fill="#3A1530">GAL</text>'
             '<text x="270" y="262" font-family="sans-serif" font-size="40" fill="#3A1530">Get a life.</text>'
             '<text x="270" y="318" font-family="sans-serif" font-size="30" fill="#6A3A5A">Less phone, more life.</text>')
    svg = to_svg(phone + cover_art, 1024, BACKGROUND).replace("<svg ", "<svg ", 1)
    svg = svg.replace('viewBox="0 0 1024 1024" width="1024" height="1024"', 'viewBox="0 0 1024 500" width="1024" height="500"')
    svg = svg.replace("\n</svg>", "\n" + words + "</svg>")
    PREVIEW.mkdir(exist_ok=True)
    (PREVIEW / "cover.svg").write_text(svg, encoding="utf-8")
    try:
        import cairosvg
        cairosvg.svg2png(bytestring=svg.encode(), write_to=str(PREVIEW / "cover.png"), output_width=1024)
    except ImportError:
        pass

    # Notification icon: white silhouette, 24dp.
    stat = fit(silhouette(), SIL_BOX, 24, (1, 1, 23, 23))
    write("ic_stat_sidekick.xml", to_vector(stat, 24, 24, "Notification icon: the elephant's silhouette."))
    preview("ic_stat", to_svg(stat, 24, "#333333"))


if __name__ == "__main__":
    main()
