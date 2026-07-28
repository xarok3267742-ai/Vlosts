#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import random
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATHS = (
    ROOT / "app/src/main/assets/slots_config.json",
    ROOT / "app/src/test/resources/slots_config.json",
)
REFERENCE_SLOT_ID = "violet_fortune"
MIN_WILD_SCATTER_DISTANCE = 4
MAX_SHUFFLE_ATTEMPTS = 10_000


def canonical_signature(slot: dict) -> tuple[tuple[int, ...], ...]:
    symbol_indexes = {symbol: index for index, symbol in enumerate(slot["symbols"])}
    return tuple(
        tuple(symbol_indexes[symbol] for symbol in strip)
        for strip in slot["reelStrips"]
    )


def cyclic_distance(left: int, right: int, size: int) -> int:
    direct = abs(left - right)
    return min(direct, size - direct)


def valid_strip(strip: list[str], wild: str, scatter: str) -> bool:
    if any(symbol == strip[(index + 1) % len(strip)] for index, symbol in enumerate(strip)):
        return False
    wild_index = strip.index(wild)
    scatter_index = strip.index(scatter)
    return cyclic_distance(wild_index, scatter_index, len(strip)) >= MIN_WILD_SCATTER_DISTANCE


def diversified_strip(slot: dict, reel_index: int, source: list[str]) -> list[str]:
    counts = Counter(source)
    bag = [
        symbol
        for symbol in slot["symbols"]
        for _ in range(counts[symbol])
    ]
    seed_material = f"v-slot-reel-v1:{slot['id']}:{reel_index}".encode("utf-8")
    seed = int.from_bytes(hashlib.sha256(seed_material).digest()[:8], "big")
    rng = random.Random(seed)
    for _ in range(MAX_SHUFFLE_ATTEMPTS):
        candidate = bag.copy()
        rng.shuffle(candidate)
        if valid_strip(candidate, slot["wild"], slot["scatter"]):
            assert Counter(candidate) == counts
            return candidate
    raise RuntimeError(f"Could not diversify {slot['id']} reel {reel_index}")


def diversify(config: dict) -> dict:
    existing_signatures: set[tuple[tuple[int, ...], ...]] = set()
    for slot in config["slots"]:
        if slot["id"] != REFERENCE_SLOT_ID:
            slot["reelStrips"] = [
                diversified_strip(slot, reel_index, strip)
                for reel_index, strip in enumerate(slot["reelStrips"])
            ]
        signature = canonical_signature(slot)
        if signature in existing_signatures:
            raise RuntimeError(f"Slot {slot['id']} still duplicates another reel rhythm")
        existing_signatures.add(signature)
    return config


def main() -> None:
    source = json.loads(CONFIG_PATHS[0].read_text(encoding="utf-8"))
    output = json.dumps(diversify(source), ensure_ascii=False, indent=2) + "\n"
    for path in CONFIG_PATHS:
        path.write_text(output, encoding="utf-8")
        print(path.relative_to(ROOT))


if __name__ == "__main__":
    main()
