#!/usr/bin/env python3
"""
Builds app/src/main/assets/phrases.json from the batch files next to this script.

Batch line format:   <tier> <tag codes> | <text>
    2 lmr | Still here? Bold.
Blank lines and lines starting with # are ignored.

Tag codes (every phrase also gets "general"):
    s short_session   l long_session   m marathon
    a morning         n late_night     w weekend
    f first_nag       r repeat_nag
    u trend_up        d trend_down     x new_record    g streak_good

A code of "-" means the phrase is general only.



    e give_up         The sign-off on the last card a session's cap allows: the app announcing it is done
                      nagging until next time. Card 12 of a session only. Like j, each
                      stands alone with no "general", so no earlier card and no fallback can reach one.
                      These are exempt from the short-line preference and may run a little longer.

    j work_hours      Monday to Friday, 9:00 to 16:59. For lines that are only true then ("No job?").
                      They carry no other tag and no "general", so no match or fallback can show them
                      at 2am on a Sunday.

    o owl_mode        A reaction to the user choosing a threshold over an hour. Shown once in the app,
                      never on an overlay card, so it carries no other tag, not even "general".

    p app_roast       Said by Sidekick when asked to open a social app the user opens a lot, before it smashes
                      the app's crate open. Has {app} where the app's name goes. Never on a card, so it carries
                      no other tag, not even "general".

Every phrase gets a "length" from its word count (whitespace separated): short is under 6 words,
medium 6 to 12, long 13 or more. The app mixes lengths by moment (see LengthMix.kt). Batches whose
file name contains "_short" may only hold short lines.

Tagging rule, because the engine matches a phrase when ANY of its tags matches the moment:
if the wording depends on a fact ("again", "this late", "your worst day yet"), tag it only with
codes that make that fact true. Wording that holds anywhere may carry every situation it suits.

Batches are processed in order and deduplicated as they go on a normalised form
(lowercase, punctuation and whitespace removed). Near duplicates are reported as warnings.

Packs. Every entry gets "pack", the home screen tab it belongs to:
    spicy   everything in batch_*.txt (the tiered, tagged lines above, owl_mode included)
    cry     everything in cry_*.txt: "You may cry" roasts, one per line, no tier or codes, just the text.
            They are written to hold at any moment (the app adds a time-of-day greeting to the first card
            of a session), so wording tied to a time, a day or a repeat is refused. The tone is allowed to be
            mean, "loser" included, but never personal, bodily, religious, political or controversial.
            Files with "giveup" in the name hold the pack's sign-offs for card 12, the last card of a session.
            They get the give_up tag alone, no "general", so no ordinary roast card can draw one.

App roasts live in app_*.txt, in the tiered batch format with the p code. They are read last, so adding
lines there never renumbers anything else (a phrase's id is its position).

Usage:  python build_phrases.py [--check-only]
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "app" / "src" / "main" / "assets" / "phrases.json"

CODES = {
    "s": "short_session", "l": "long_session", "m": "marathon",
    "a": "morning", "n": "late_night", "w": "weekend",
    "f": "first_nag", "r": "repeat_nag",
    "u": "trend_up", "d": "trend_down", "x": "new_record", "g": "streak_good",
    "j": "work_hours",

    "e": "give_up",
    "o": "owl_mode",
    "p": "app_roast",
}
OWL = "owl_mode"
WORK = "work_hours"
APP = "app_roast"
# Tags that must stand alone: no other tag, no "general".
SOLO = {"o": OWL, "j": WORK, "e": "give_up", "p": APP}
TAG_ORDER = ["general"] + list(CODES.values())
NAG_TAGS = [t for t in TAG_ORDER if t not in (OWL, APP)]
APP_TARGET_PER_TIER = 50
APP_MAX_CHARS = 90           # with {app} replaced by a ten letter name, so it fits a speech bubble
APP_SAMPLE_NAME = "Tenletters"
LENGTHS = ["short", "medium", "long"]

# Tone guard: the app is a joke, never about health, bodies or mental state, never cruel about the person.
BANNED = [
    r"\bfat\b", r"\bweight\b", r"\bobes", r"\bdiet", r"\bhealth", r"\bsick", r"\bill\b", r"\bdisease",
    r"\bdepress", r"\banxi", r"\bmental", r"\btherap", r"\bsuicid", r"\blonel", r"\bcrazy\b", r"\binsane",
    r"\bpsycho", r"\baddict", r"\bbrain ?rot", r"\bugly\b", r"\bstupid", r"\bidiot", r"\bloser", r"\bpathetic",
    r"\bworthless", r"\bdumb\b", r"\beyes?ight", r"\bposture", r"\bneck\b", r"\bspine", r"\bmoron",
    r"\bkill yourself", r"\bdie\b", r"\bdying\b", r"\bdead\b", r"\bdeath", r"\bdoctor", r"\bmedic",
    r"\binsomnia", r"\bsleep deprivation", r"\bdoom", r"\bzombie", r"\bsad\b",
]

# Roasts may call you a loser. Everything else in BANNED still applies to them.
CRY_ALLOWED = {r"\bloser", r"\bpathetic"}
CRY_BANNED = [
    # Personal: family, relationships, money, work status, looks, intelligence.
    r"\bmom(s|my)?\b", r"\bmum\b", r"\bmother", r"\bdad\b", r"\bfather", r"\bparent", r"\bfamily", r"\bwife", r"\bhusband",
    r"\bgirlfriend", r"\bboyfriend", r"\bdivorc", r"\b(still|forever|so|very|being|stay|staying) single\b", r"\bdating\b", r"\bmarri", r"\bkids?\b", r"\bchild",
    r"\bfriends?\b", r"\bfriendless", r"\b(all|so|always|forever|eat|eating|sit|sitting|live|living|end up) alone\b", r"\bno one loves", r"\bnobody loves", r"\bbroke\b", r"\bpoor\b",
    r"\bjob\b", r"\bunemploy", r"\bfired\b", r"\bsalary", r"\b(your|my|the office) boss\b", r"\bface\b", r"\bhair", r"\bbald", r"\bsmell",
    r"\bstink", r"\bteeth", r"\bskin\b", r"\bbelly", r"\bbutt\b", r"\bbody\b", r"\bbrains?\b", r"\biq\b", r"\bvirgin",
    # Religion, politics and other controversy.
    r"\bgod\b", r"\bgods\b", r"\bjesus", r"\ballah", r"\bchurch", r"\bpray", r"\breligio", r"\bbible", r"\bheaven",
    r"\bhell\b", r"\bsatan", r"\bdevil", r"\bpolitic", r"\belection", r"\bvot(e|ing)\b", r"\bpresident", r"\bgovernment",
    r"\bdemocrat", r"\brepublican", r"\bliberal", r"\bconservative", r"\bwar\b", r"\bnazi", r"\bterror", r"\bguns?\b",
    r"\bshoot", r"\bbomb", r"\brac(e|ist|ism)\b", r"\bgay\b", r"\blesbian", r"\btrans\b", r"\bgender", r"\bimmigra",
    r"\babortion", r"\bdrugs?\b", r"\bweed\b", r"\bdrunk", r"\balcohol", r"\bbeer\b", r"\bwine\b", r"\bsex", r"\bporn",
    r"\bnude", r"\bkill", r"\bmurder", r"\bblood",
    # Profanity: the card is seen by whoever is next to you.
    r"\bfuck", r"\bshit", r"\bbitch", r"\bass\b", r"\basshole", r"\bdick\b", r"\bcunt", r"\bbastard",
    # Facts the moment may not support: the greeting handles time of day.
    r"\bmorning", r"\bafternoon", r"\bevening", r"\btonight", r"\bnight", r"\bmidnight", r"\bweekend",
    r"\b(mon|tues|wednes|thurs|fri|satur|sun)day", r"\bagain\b", r"\bhours?\b", r"\ball day\b", r"\b\d+ ?(am|pm)\b",
    r"\brecord\b", r"\bbedtime", r"\bsunrise", r"\bsunset", r"\bbreakfast", r"\blunch", r"\bdinner",
]
CRY_TARGET = 2677          # ordinary roasts
CRY_GIVEUP_TARGET = 80     # sign-offs for the last card of a session
CRY_MAX_CHARS = 110  # leaves room for the greeting on a first card

MIN_TIER_TAG = 120  # the brief's aim; reported, not enforced
TARGET = 1380       # nag phrases
OWL_TARGET_PER_TIER = 20


def normalise(text: str) -> str:
    return re.sub(r"[\W_]+", "", text.lower())


def length_of(text: str) -> str:
    n = len(text.split())
    return "short" if n < 6 else "medium" if n <= 12 else "long"


# Sentence patterns. Linking words and punctuation stay, every other word becomes "_", so "I have seen a
# doorknob do more." and "I have seen a turnip do more." share one pattern. More than MAX_PER_PATTERN lines
# on one pattern means fill-in-the-blank writing. PhraseCatalogTest applies the same rule to phrases.json.
PATTERN_WORDS = set("""
a an the this that these those
i me my mine you your yours he him his she her it its we us our they them their
is am are was were be been being do does did done have has had having
will would can could shall should may might must
and or but nor so yet if then than as because while when where how what who whom which why
of to in on at by for with from up down out off over under into onto about after before through
not no nothing nobody none any some every each all more most less least very too just even only still
please here there now today again ever never always
""".split())
PATTERN_PUNCTUATION = set(".,?!:;")
MAX_PER_PATTERN = 15


def pattern_of(text: str) -> str:
    out = []
    for tok in re.findall(r"[A-Za-z']+|[^\sA-Za-z']", text.lower()):
        if tok[0].isalpha() or tok[0] == "'":
            out.append(tok if tok in PATTERN_WORDS else "_")
        elif tok in PATTERN_PUNCTUATION:
            out.append(tok)
    return " ".join(out)


def words(text: str) -> set:
    return set(re.findall(r"[a-z']+", text.lower())) - {"the", "a", "an", "you", "your", "is", "it", "to", "of", "and", "that", "this", "in", "on"}


def main() -> int:
    check_only = "--check-only" in sys.argv
    warnings = []
    batches = sorted(HERE.glob("batch_*.txt"))
    phrases, seen, errors = [], {}, []
    banned_res = [re.compile(b, re.IGNORECASE) for b in BANNED]

    def parse_tiered(batch):
        added = dupes = 0
        for lineno, raw in enumerate(batch.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            where = f"{batch.name}:{lineno}"
            m = re.fullmatch(r"([123])\s+([a-z\-]+)\s*\|\s*(.+)", line)
            if not m:
                errors.append(f"{where}: bad line format: {line}")
                continue
            tier, codes, text = int(m.group(1)), m.group(2), m.group(3).strip()
            tags = ["general"]
            if codes != "-":
                for c in codes:
                    if c not in CODES:
                        errors.append(f"{where}: unknown tag code '{c}'")
                    elif CODES[c] not in tags:
                        tags.append(CODES[c])
            for code, solo in SOLO.items():
                if solo in tags:
                    if codes != code:
                        errors.append(f"{where}: {solo} lines take no other tag codes")
                    tags = [solo]
            for rx in banned_res:
                if rx.search(text):
                    errors.append(f"{where}: banned topic /{rx.pattern}/ in: {text}")
            if len(text) > 140:
                errors.append(f"{where}: too long for the card ({len(text)} chars)")
            if APP in tags:
                if "{app}" not in text:
                    errors.append(f"{where}: app_roast lines need {{app}} where the app's name goes: {text}")
                filled = text.replace("{app}", APP_SAMPLE_NAME)
                if len(filled) > APP_MAX_CHARS:
                    errors.append(f"{where}: too long for the bubble ({len(filled)} chars with a ten letter name, max {APP_MAX_CHARS})")
            elif "{" in text or "}" in text:
                errors.append(f"{where}: only app_roast lines may use {{app}}: {text}")
            length = length_of(text)
            if "_short" in batch.name and length != "short":
                errors.append(f"{where}: {len(text.split())} words is not short (under 6): {text}")
            key = normalise(text)
            if key in seen:
                dupes += 1
                print(f"  dupe dropped {where} (first seen {seen[key]}): {text}")
                continue
            seen[key] = where
            tags.sort(key=TAG_ORDER.index)
            phrases.append({"id": len(phrases) + 1, "text": text, "tier": tier, "length": length, "pack": "spicy", "tags": tags, "_where": where})
            added += 1
        print(f"{batch.name}: +{added} (dupes dropped: {dupes}) total {len(phrases)}")

    for batch in batches:
        parse_tiered(batch)

    cry_res = [re.compile(b, re.IGNORECASE) for b in BANNED if b not in CRY_ALLOWED] + \
        [re.compile(b, re.IGNORECASE) for b in CRY_BANNED]
    cry_batches = sorted(HERE.glob("cry_*.txt"))
    for batch in cry_batches:
        added = dupes = 0
        for lineno, raw in enumerate(batch.read_text(encoding="utf-8").splitlines(), 1):
            text = raw.strip()
            if not text or text.startswith("#"):
                continue
            where = f"{batch.name}:{lineno}"
            if "|" in text:
                errors.append(f"{where}: roast lines are plain text, no tier or codes: {text}")
            cry_tags = ["give_up"] if "giveup" in batch.name else ["general"]
            for rx in cry_res:
                if rx.search(text):
                    errors.append(f"{where}: not allowed in a roast /{rx.pattern}/ in: {text}")
            if "_short" in batch.name and length_of(text) != "short":
                errors.append(f"{where}: {len(text.split())} words is not short (under 6): {text}")
            if len(text) > CRY_MAX_CHARS:
                errors.append(f"{where}: roast too long ({len(text)} chars, max {CRY_MAX_CHARS}): {text}")
            key = normalise(text)
            if key in seen:
                dupes += 1
                errors.append(f"{where}: duplicate of {seen[key]}: {text}")
                continue
            seen[key] = where
            phrases.append({"id": len(phrases) + 1, "text": text, "tier": 3, "length": length_of(text), "pack": "cry", "tags": cry_tags, "_where": where})
            added += 1
        print(f"{batch.name}: +{added} (dupes: {dupes}) total {len(phrases)}")

    # App roasts come last so adding them never renumbers the lines above (ids are positions).
    app_batches = sorted(HERE.glob("app_*.txt"))
    for batch in app_batches:
        parse_tiered(batch)

    # Near-duplicate warnings: very high word overlap.
    wsets = [(p, words(p["text"])) for p in phrases]
    near = 0
    for i in range(len(wsets)):
        pi, wi = wsets[i]
        if len(wi) < 4:
            continue
        for j in range(i + 1, len(wsets)):
            pj, wj = wsets[j]
            if len(wj) < 4:
                continue
            jac = len(wi & wj) / len(wi | wj)
            if jac >= 0.7 and (pi["pack"] == "cry" or pj["pack"] == "cry" or "--all-near" in sys.argv or not cry_batches):
                near += 1
                print(f"  near-duplicate ({jac:.2f}): {pi['_where']} '{pi['text']}' ~ {pj['_where']} '{pj['text']}'")

    final = len(batches) >= 10
    nag = [p for p in phrases if OWL not in p["tags"] and APP not in p["tags"] and p["pack"] == "spicy"]
    owl = [p for p in phrases if OWL in p["tags"]]
    app = [p for p in phrases if APP in p["tags"]]
    cry = [p for p in phrases if p["pack"] == "cry" and "give_up" not in p["tags"]]
    cry_giveup = [p for p in phrases if p["pack"] == "cry" and "give_up" in p["tags"]]
    # Coverage matrix, nag phrases only.
    print("\ncoverage (nag phrases carrying tag, per tier)")
    print(f"{'tag':<15}{'t1':>6}{'t2':>6}{'t3':>6}")
    thin = []
    for tag in NAG_TAGS:
        counts = [sum(1 for p in nag if p["tier"] == t and tag in p["tags"]) for t in (1, 2, 3)]
        print(f"{tag:<15}{counts[0]:>6}{counts[1]:>6}{counts[2]:>6}")
        for t, c in zip((1, 2, 3), counts):
            if c == 0:
                (errors if final else warnings).append(f"tag {tag} has no phrases at tier {t}")
            elif c < MIN_TIER_TAG:
                thin.append(f"{tag}/t{t}={c}")
    owl_counts = [sum(1 for p in owl if p["tier"] == t) for t in (1, 2, 3)]
    print(f"{OWL:<15}{owl_counts[0]:>6}{owl_counts[1]:>6}{owl_counts[2]:>6}   (in-app only, not overlay cards)")
    print("\nlength (nag phrases per tier)")
    print(f"{'length':<15}{'t1':>6}{'t2':>6}{'t3':>6}{'all':>6}")
    for length in LENGTHS:
        counts = [sum(1 for p in nag if p["tier"] == t and p["length"] == length) for t in (1, 2, 3)]
        print(f"{length:<15}{counts[0]:>6}{counts[1]:>6}{counts[2]:>6}{sum(counts):>6}")
    print("\nshort nag phrases carrying tag, per tier")
    for tag in NAG_TAGS:
        counts = [sum(1 for p in nag if p["tier"] == t and tag in p["tags"] and p["length"] == "short") for t in (1, 2, 3)]
        print(f"{tag:<15}{counts[0]:>6}{counts[1]:>6}{counts[2]:>6}")
    avg_tags = sum(len([t for t in p["tags"] if t != "general"]) for p in nag) / max(1, len(nag))
    print(f"\nnag phrases: {len(nag)}  owl_mode lines: {len(owl)}  avg specific tags: {avg_tags:.2f}  near-duplicates: {near}")
    if thin:
        print(f"below {MIN_TIER_TAG}: {', '.join(thin)}")
    cry_lengths = {length: sum(1 for p in cry if p["length"] == length) for length in LENGTHS}
    print(f"roast lines (You may cry): {len(cry)}  by length: {cry_lengths}  give_up sign-offs: {len(cry_giveup)}")

    if final and len(nag) != TARGET:
        errors.append(f"expected exactly {TARGET} nag phrases, got {len(nag)}")
    patterns = {}
    for p in phrases:
        patterns.setdefault(pattern_of(p["text"]), []).append(p)
    crowded = sorted(((k, v) for k, v in patterns.items() if len(v) > MAX_PER_PATTERN), key=lambda kv: -len(kv[1]))
    largest = max(len(v) for v in patterns.values()) if patterns else 0
    print(f"sentence patterns: {len(patterns)} for {len(phrases)} lines, largest group {largest} (max {MAX_PER_PATTERN})")
    for key, group in crowded:
        examples = "; ".join(p["text"] for p in group[:3])
        errors.append(f"{len(group)} lines share the pattern '{key}' (max {MAX_PER_PATTERN}), e.g. {examples}")
    if cry_batches and len(cry) != CRY_TARGET:
        errors.append(f"expected exactly {CRY_TARGET} roast lines, got {len(cry)}")
    if cry_batches and len(cry_giveup) != CRY_GIVEUP_TARGET:
        errors.append(f"expected exactly {CRY_GIVEUP_TARGET} You may cry give_up lines, got {len(cry_giveup)}")
    if owl and any(c != OWL_TARGET_PER_TIER for c in owl_counts):
        errors.append(f"expected {OWL_TARGET_PER_TIER} owl_mode lines per tier, got {owl_counts}")
    app_counts = [sum(1 for p in app if p["tier"] == t) for t in (1, 2, 3)]
    print(f"app_roast lines per tier: {app_counts}")
    if app and any(c != APP_TARGET_PER_TIER for c in app_counts):
        errors.append(f"expected {APP_TARGET_PER_TIER} app_roast lines per tier, got {app_counts}")
    for w in warnings:
        print("  warning: " + w)
    if errors:
        print("\nERRORS:")
        for e in errors:
            print("  " + e)
        return 1
    if not check_only:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        clean = [{k: v for k, v in p.items() if not k.startswith("_")} for p in phrases]
        OUT.write_text(json.dumps(clean, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
