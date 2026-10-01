"""
Draws GetALife's four roast faces (smug, bored, disappointed, horrified) on the Sidekick blob.

Writes one VectorDrawable per face into app/src/main/res/drawable/mascot_<face>.xml and a side-by-side
preview to tools/mascot/blob_faces_preview.svg. The drawables and the preview come from the same shape list,
so what you see in the preview is what ships.

The blob uses the concept C colours (mint #5CE79B to #1FA463, rim #14663C, ink #0E2B1B). From the old
GetALife mascot it keeps the four-face escalation, the thick expressive eyebrows and the sweat drop for
horrified. The live overlay blob draws the same four moods in code (SidekickView.Mood); keep them in step.

Run from the GAL folder:  python tools/mascot/generate_blob_faces.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAWABLES = ROOT / "app/src/main/res/drawable"
PREVIEW = Path(__file__).resolve().parent / "blob_faces_preview.svg"

SIZE = 120
MINT_LIGHT = "#5CE79B"
MINT_DARK = "#1FA463"
RIM = "#14663C"
INK = "#0E2B1B"
WHITE = "#FFFFFF"
SWEAT = "#7EC8F0"
TONGUE = "#F28C28"

# Body: a rounded square, like SidekickView (corner radius about 0.42 of the side).
BODY_TOP, BODY_BOTTOM, BODY_LEFT, BODY_RIGHT, CORNER = 22, 104, 16, 104, 36


def rounded_rect(l, t, r, b, c):
    return (
        f"M{l + c},{t} H{r - c} A{c},{c} 0 0 1 {r},{t + c} V{b - c} A{c},{c} 0 0 1 {r - c},{b} "
        f"H{l + c} A{c},{c} 0 0 1 {l},{b - c} V{t + c} A{c},{c} 0 0 1 {l + c},{t} Z"
    )


def ellipse(cx, cy, rx, ry):
    return f"M{cx - rx},{cy} A{rx},{ry} 0 1 0 {cx + rx},{cy} A{rx},{ry} 0 1 0 {cx - rx},{cy} Z"


def circle(cx, cy, r):
    return ellipse(cx, cy, r, r)


# A shape is (path, fill, stroke, stroke_width, fill_alpha). fill "BODY" means the body gradient, which is
# laid out in viewport space, so an eyelid filled with it matches the skin behind it exactly.
def fill(path, color, alpha=1.0):
    return (path, color, None, 0, alpha)


def line(path, color=INK, width=5.0):
    return (path, None, color, width, 1.0)


def body_shapes():
    return [
        # Soft ground shadow.
        fill(ellipse(60, 112, 32, 3.5), INK, 0.12),
        # Little arms, behind the body.
        (ellipse(16, 78, 8, 6.5), "BODY", RIM, 3.0, 1.0),
        (ellipse(104, 78, 8, 6.5), "BODY", RIM, 3.0, 1.0),
        (rounded_rect(BODY_LEFT, BODY_TOP, BODY_RIGHT, BODY_BOTTOM, CORNER), "BODY", RIM, 3.0, 1.0),
        # Shine, top left.
        line("M31,43 Q33,36 39,33", WHITE, 4.0)[:4] + (0.45,),
    ]


def eye(cx, cy, rx, ry, pupil_dx, pupil_dy, pupil_r, lid):
    """lid is how far the upper lid comes down, 0 (open) to 1 (shut)."""
    shapes = [
        fill(ellipse(cx, cy, rx, ry), WHITE),
        fill(circle(cx + pupil_dx, cy + pupil_dy, pupil_r), INK),
        fill(circle(cx + pupil_dx + pupil_r * 0.35, cy + pupil_dy - pupil_r * 0.4, max(1.2, pupil_r * 0.3)), WHITE),
    ]
    if lid > 0:
        top = cy - ry - 1.5
        edge = cy - ry + 2 * ry * lid
        # A skin-coloured cap over the top of the eye with a slightly curved lower edge.
        shapes.append(fill(f"M{cx - rx - 1.5},{top} H{cx + rx + 1.5} V{edge} Q{cx},{edge + 2.5} {cx - rx - 1.5},{edge} Z", "BODY"))
        shapes.append(line(f"M{cx - rx},{edge} Q{cx},{edge + 2.5} {cx + rx},{edge}", INK, 2.6))
    return shapes


LEFT_EYE, RIGHT_EYE, EYE_Y = 43, 77, 60


def face(name):
    s = []
    if name == "smug":
        # One brow flat, one arched up; lids half down, eyes sliding sideways; a lopsided smirk.
        s += [line("M32,43 Q42,41 52,43"), line("M67,38 Q77,30 88,36")]
        s += eye(LEFT_EYE, EYE_Y, 10.5, 12, 3.5, 2.5, 5.2, 0.42)
        s += eye(RIGHT_EYE, EYE_Y, 10.5, 12, 3.5, 2.5, 5.2, 0.42)
        s += [line("M45,85 Q60,92 76,80", INK, 4.6), line("M76,80 L79,77", INK, 4.0)]
    elif name == "bored":
        # Flat low brows, heavy lids, pupils sagging, a straight-line mouth.
        s += [line("M32,45 H52"), line("M68,45 H88")]
        s += eye(LEFT_EYE, EYE_Y + 1, 10.5, 11.5, 0, 4.5, 5.0, 0.58)
        s += eye(RIGHT_EYE, EYE_Y + 1, 10.5, 11.5, 0, 4.5, 5.0, 0.58)
        s += [line("M49,86 H71", INK, 4.6)]
    elif name == "disappointed":
        # Inner brow ends lifted, lids a little down, looking at the floor, a frown.
        s += [line("M31,47 Q41,44 52,39"), line("M68,39 Q79,44 89,47")]
        s += eye(LEFT_EYE, EYE_Y + 1, 10.5, 12, 0, 4.5, 5.0, 0.26)
        s += eye(RIGHT_EYE, EYE_Y + 1, 10.5, 12, 0, 4.5, 5.0, 0.26)
        s += [line("M47,90 Q60,80 73,90", INK, 4.6)]
    elif name == "horrified":
        # Brows shot up, eyes wide with tiny pupils, mouth hanging open, and the sweat drop.
        s += [line("M31,37 Q41,28 53,34"), line("M67,34 Q79,28 89,37")]
        s += eye(LEFT_EYE - 1, EYE_Y, 12.5, 14.5, 0, 0.5, 3.4, 0)
        s += eye(RIGHT_EYE + 1, EYE_Y, 12.5, 14.5, 0, 0.5, 3.4, 0)
        s += [fill(ellipse(60, 89, 8, 9.5), INK), fill(ellipse(60, 94, 4.8, 3.6), TONGUE)]
        s += [(f"M101,20 Q108,31 101,35 Q94,31 101,20 Z", SWEAT, RIM, 1.6, 1.0)]
    else:
        raise ValueError(name)
    return body_shapes() + s


def gradient_xml():
    return (
        '        <aapt:attr name="android:fillColor">\n'
        f'            <gradient android:type="linear" android:startX="0" android:startY="{BODY_TOP}" '
        f'android:endX="0" android:endY="{BODY_BOTTOM}" android:startColor="{MINT_LIGHT}" '
        f'android:endColor="{MINT_DARK}" android:tileMode="clamp" />\n'
        "        </aapt:attr>\n"
    )


def to_vector(shapes, title):
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {title}. Generated by tools/mascot/generate_blob_faces.py; edit that, not this. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{SIZE}dp"',
        f'    android:height="{SIZE}dp"',
        f'    android:viewportWidth="{SIZE}"',
        f'    android:viewportHeight="{SIZE}">',
    ]
    for path, fill_color, stroke, width, alpha in shapes:
        attrs = [f'android:pathData="{path}"']
        if fill_color and fill_color != "BODY":
            attrs.append(f'android:fillColor="{fill_color}"')
        if alpha < 1.0:
            attrs.append(f'android:fillAlpha="{alpha}"' if fill_color else f'android:strokeAlpha="{alpha}"')
        if stroke:
            attrs += [
                f'android:strokeColor="{stroke}"',
                f'android:strokeWidth="{width}"',
                'android:strokeLineCap="round"',
                'android:strokeLineJoin="round"',
            ]
        body = "\n        ".join(attrs)
        if fill_color == "BODY":
            out.append(f"    <path\n        {body}>\n{gradient_xml()}    </path>")
        else:
            out.append(f"    <path\n        {body} />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def to_svg_group(shapes, dx, label):
    parts = [f'<g transform="translate({dx} 0)">']
    for path, fill_color, stroke, width, alpha in shapes:
        f = "url(#body)" if fill_color == "BODY" else (fill_color or "none")
        a = f' fill="{f}"'
        if alpha < 1.0:
            a += f' fill-opacity="{alpha}"' if fill_color else f' stroke-opacity="{alpha}"'
        if stroke:
            a += f' stroke="{stroke}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"'
        parts.append(f'<path d="{path}"{a}/>')
    parts.append(f'<text x="60" y="134" font-family="sans-serif" font-size="11" text-anchor="middle" fill="{INK}">{label}</text>')
    parts.append("</g>")
    return "\n".join(parts)


FACES = ["smug", "bored", "disappointed", "horrified"]

if __name__ == "__main__":
    groups = []
    for i, name in enumerate(FACES):
        shapes = face(name)
        (DRAWABLES / f"mascot_{name}.xml").write_text(to_vector(shapes, f"Sidekick blob, {name} roast face"), encoding="utf-8")
        groups.append(to_svg_group(shapes, i * 130, name))
    PREVIEW.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {len(FACES) * 130} 140" width="{len(FACES) * 260}" height="280">\n'
        f'<defs><linearGradient id="body" gradientUnits="userSpaceOnUse" x1="0" y1="{BODY_TOP}" x2="0" y2="{BODY_BOTTOM}">'
        f'<stop offset="0" stop-color="{MINT_LIGHT}"/><stop offset="1" stop-color="{MINT_DARK}"/></linearGradient></defs>\n'
        f'<rect width="100%" height="100%" fill="#DFF7EA"/>\n' + "\n".join(groups) + "\n</svg>\n",
        encoding="utf-8",
    )
    print("wrote", ", ".join(f"mascot_{n}.xml" for n in FACES), "and", PREVIEW.name)
