#!/usr/bin/env python3
"""Build the Playing Cards resource pack: python3 resourcepack/build.py [version]

Zips pack/ into dist/Playing-Cards-<version>.zip with fixed timestamps (so the same sources always
give the same SHA-1) and adds two generated files:

  pack.mcmeta
  assets/minecraft/models/item/clock.json
      Vanilla's clock model (vanilla/clock.json, from the 1.21.1 client) with extra overrides that
      map CustomModelData 21000+ to the card models. Servers older than 1.21.2 can't send the
      item_model component, so Blackjack textures cards that way there. The order below must match
      CardModels.MODEL_NAMES in the plugin; only ever append. 1.21.4+ clients never read this file
      (their clock uses assets/minecraft/items/clock.json), so it doesn't affect them.
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

VERSION = sys.argv[1] if len(sys.argv) > 1 else "1.2"
HERE = Path(__file__).resolve().parent
LEGACY_CMD_BASE = 21000
RANKS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k"]
MODEL_NAMES = ["back"] + [suit + rank for suit in "shdc" for rank in RANKS] + ["j"]
FIXED_TIME = (2026, 1, 1, 0, 0, 0)

PACK_MCMETA = {
    "pack": {
        # 15 = 1.20. supported_formats is read by 1.20.2-1.21.8, min/max_format by 1.21.9+
        "pack_format": 15,
        "supported_formats": [15, 255],
        "min_format": 15,
        "max_format": 255,
        "description": f"Playing cards for the Blackjack plugin §7v{VERSION}",
    }
}


def legacy_clock_model():
    model = json.loads((HERE / "vanilla" / "clock.json").read_text())
    for index, name in enumerate(MODEL_NAMES):
        model["overrides"].append({
            "predicate": {"custom_model_data": LEGACY_CMD_BASE + index},
            "model": f"playing_cards:item/card/{name}",
        })
    return model


def add(zf, name, data):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data)


def main():
    out = HERE / "dist" / f"Playing-Cards-{VERSION}.zip"
    out.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(out, "w") as zf:
        add(zf, "pack.mcmeta", json.dumps(PACK_MCMETA, indent=2) + "\n")
        for path in sorted(p for p in (HERE / "pack").rglob("*") if p.is_file()):
            add(zf, path.relative_to(HERE / "pack").as_posix(), path.read_bytes())
        add(zf, "assets/minecraft/models/item/clock.json", json.dumps(legacy_clock_model(), indent=2) + "\n")

    data = out.read_bytes()
    print(out)
    print("sha1  ", hashlib.sha1(data).hexdigest())
    print("sha512", hashlib.sha512(data).hexdigest())
    print("size  ", len(data))


if __name__ == "__main__":
    main()
