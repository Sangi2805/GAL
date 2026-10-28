"""
Builds the files the iOS app needs from the Android app's sources, so the two never drift apart:

  ios/Shared/Phrases/*.txt         one roast line per line, read at a random spot by the small iOS extensions
                                   (they have about 6 MB of memory, too little to parse all of phrases.json)
  ios/GAL/Assets.xcassets          the app icon (1024 px, no transparency, as Apple requires)
  ios/Shared/SharedAssets.xcassets the elephant picture shown on the "Nooo" shield

Run from the GAL folder:  python tools/ios/prepare_assets.py   (needs cairosvg for the pictures)
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
sys.path.insert(0, str(ROOT / "tools" / "mascot"))
import generate_elephant as art  # noqa: E402


def write_lines(name, texts):
    path = IOS / "Shared" / "Phrases" / f"{name}.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    clean = [t.replace("\n", " ").strip() for t in texts if t.strip()]
    # A leading newline means a read starting at byte 0 still finds a whole line after the first "\n".
    path.write_text("\n" + "\n".join(clean) + "\n", encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)} ({len(clean)} lines)")


def phrases():
    data = json.loads((ROOT / "app/src/main/assets/phrases.json").read_text(encoding="utf-8"))

    def pick(pack, want=None, skip=()):
        return [p["text"] for p in data
                if p["pack"] == pack
                and (want is None or want in p["tags"])
                and not any(t in p["tags"] for t in skip)]

    special = ("give_up", "owl_mode", "app_roast")
    write_lines("spicy", pick("spicy", skip=special))
    write_lines("cry", pick("cry", skip=special))
    write_lines("giveup_spicy", pick("spicy", want="give_up"))
    write_lines("giveup_cry", pick("cry", want="give_up") or pick("spicy", want="give_up"))
    write_lines("app_roast", [p["text"] for p in data if "app_roast" in p["tags"]])


def png(svg, out, width):
    import cairosvg
    out.parent.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(out), output_width=width, output_height=width)
    print(f"wrote {out.relative_to(ROOT)}")


def contents(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")


def pictures():
    info = {"author": "xcode", "version": 1}

    # App icon: the logo on the mint background, square and opaque. iOS rounds the corners itself.
    logo = art.elephant("proud", art.TRUNK_UP, walking=True, extras=False) + [art.sparkle(452, 130, 12), art.sparkle(70, 170, 9)]
    icon_art = art.fit(logo, (40, 120, 470, 470), 1024, (150, 150, 874, 874))
    catalog = IOS / "GAL" / "Assets.xcassets"
    contents(catalog / "Contents.json", {"info": info})
    png(art.to_svg(icon_art, 1024, art.BACKGROUND), catalog / "AppIcon.appiconset" / "AppIcon-1024.png", 1024)
    contents(catalog / "AppIcon.appiconset" / "Contents.json", {
        "images": [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": info,
    })
    contents(catalog / "AccentColor.colorset" / "Contents.json", {
        "colors": [{"color": {"color-space": "srgb", "components": {"red": "1.000", "green": "0.361", "blue": "0.541", "alpha": "1.000"}}, "idiom": "universal"}],
        "info": info,
    })

    # The shield picture: the disappointed elephant, whole, on a transparent background.
    shared = IOS / "Shared" / "SharedAssets.xcassets"
    contents(shared / "Contents.json", {"info": info})
    face = art.fit(art.elephant("disappointed", art.TRUNK_REST), (50, 130, 460, 466), 360, (10, 10, 350, 350))
    png(art.to_svg(face, 360), shared / "ShieldElephant.imageset" / "ShieldElephant@3x.png", 360)
    contents(shared / "ShieldElephant.imageset" / "Contents.json", {
        "images": [
            {"idiom": "universal", "scale": "1x"},
            {"idiom": "universal", "scale": "2x"},
            {"filename": "ShieldElephant@3x.png", "idiom": "universal", "scale": "3x"},
        ],
        "info": info,
    })


if __name__ == "__main__":
    phrases()
    pictures()
