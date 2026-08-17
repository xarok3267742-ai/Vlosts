#!/usr/bin/env python3
"""Generate byte-bound provenance for canonical imagegen-derived resources."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image

from vslot_asset_toolchain import actual_versions, verify_asset_toolchain


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/legal/IMAGEGEN_DERIVATION_MANIFEST.json"
DRAWABLE = "app/src/main/res/drawable-nodpi"
COMMON_BUILD_INPUTS = (
    ("toolchain_gate", "tools/vslot_asset_toolchain.py"),
    ("python_requirement", "tools/requirements-assets.txt"),
)
SLOT_CONTROL_SOURCE_DIR = "qa/source/vslot-controls"
SLOT_CONTROL_MASTER_NAMES = (
    "vslot_btn_auto.png",
    "vslot_btn_auto_disabled.png",
    "vslot_btn_auto_pressed.png",
    "vslot_btn_back.png",
    "vslot_btn_back_disabled.png",
    "vslot_btn_back_pressed.png",
    "vslot_btn_max.png",
    "vslot_btn_max_disabled.png",
    "vslot_btn_max_pressed.png",
    "vslot_btn_minus.png",
    "vslot_btn_minus_disabled.png",
    "vslot_btn_minus_pressed.png",
    "vslot_btn_paytable.png",
    "vslot_btn_paytable_disabled.png",
    "vslot_btn_paytable_pressed.png",
    "vslot_btn_plus.png",
    "vslot_btn_plus_disabled.png",
    "vslot_btn_plus_pressed.png",
    "vslot_btn_spin.png",
    "vslot_btn_spin_disabled.png",
    "vslot_btn_spin_pressed.png",
)
SLOT_CONTROL_OUTPUT_NAMES = (
    "btn_autospin_default.webp",
    "btn_autospin_pressed.webp",
    "btn_autospin_disabled.webp",
    "btn_autospin_active.webp",
    "btn_autospin_active_pressed.webp",
    "btn_bet_minus.webp",
    "btn_bet_minus_pressed.webp",
    "btn_bet_minus_disabled.webp",
    "btn_bet_plus.webp",
    "btn_bet_plus_pressed.webp",
    "btn_bet_plus_disabled.webp",
    "btn_max_lines_default.webp",
    "btn_max_lines_pressed.webp",
    "btn_max_lines_disabled.webp",
    "btn_back_default.webp",
    "btn_back_pressed.webp",
    "paytable_button.webp",
    "spin_button_violet_default.webp",
    "spin_button_violet_pressed.webp",
    "spin_button_violet_disabled.webp",
    "spin_button_violet_free_spins_default.png",
    "spin_button_violet_free_spins_pressed.png",
    "spin_button_violet_free_spins_disabled.png",
)


def source(name: str) -> str:
    return f"qa/source/{name}"


def resource(name: str) -> str:
    return f"{DRAWABLE}/{name}"


def themed(prefix: str, suffix: str = ".webp") -> list[str]:
    return [resource(f"{prefix}{theme}{suffix}") for theme in ("violet", "roman", "neon", "pharaoh", "ocean")]


OUTPUT_GROUPS: dict[str, tuple[list[str], list[str]]] = {
    "tools/export_imagegen_slot_controls.py": (
        [resource(name) for name in SLOT_CONTROL_OUTPUT_NAMES],
        [
            "qa/source/vslot_controls_sprite_imagegen.png",
            *[f"{SLOT_CONTROL_SOURCE_DIR}/{name}" for name in SLOT_CONTROL_MASTER_NAMES],
        ],
    ),
    "tools/slice_imagegen_bonus_entry_portals.py": (
        themed("bonus_entry_portal_"),
        [source("vslot_theme_bonus_entry_portals_imagegen.png")],
    ),
    "tools/slice_imagegen_daily_bonus_countdown_charge.py": (
        [resource("daily_bonus_countdown_charge.webp")],
        [source("vslot_daily_bonus_countdown_charge_imagegen.png")],
    ),
    "tools/slice_imagegen_daily_bonus_home_buttons.py": (
        [resource("daily_bonus_ready_imagegen.webp"), resource("daily_bonus_wait_imagegen.webp")],
        [source("vslot_daily_bonus_home_buttons_imagegen.png")],
    ),
    "tools/slice_imagegen_daily_bonus_modal_panel.py": (
        [resource("daily_bonus_modal_panel_premium.webp")],
        [source("vslot_daily_bonus_modal_panel_premium_imagegen.png")],
    ),
    "tools/slice_imagegen_low_coins_modal_panel.py": (
        [resource("low_coins_modal_panel_premium.webp")],
        [source("vslot_low_coins_modal_panel_premium_imagegen.png")],
    ),
    "tools/slice_imagegen_paytable_modal_panels.py": (
        themed("paytable_modal_panel_"),
        [source("vslot_paytable_theme_modal_panels_imagegen.png")],
    ),
    "tools/slice_imagegen_privacy_command_buttons.py": (
        [resource("btn_privacy_default_premium.webp"), resource("btn_privacy_pressed_premium.webp")],
        [
            source("vslot_btn_privacy_default_premium_imagegen.png"),
            source("vslot_btn_privacy_pressed_premium_imagegen.png"),
        ],
    ),
    "tools/slice_imagegen_privacy_loading_overlay.py": (
        [resource("privacy_loading_scan_rail.webp"), resource("privacy_loading_shield.webp")],
        [source("vslot_privacy_loading_overlay_imagegen.png")],
    ),
    "tools/slice_imagegen_privacy_loading_sweep.py": (
        [resource("privacy_loading_sweep.webp")],
        [source("vslot_privacy_loading_sweep_imagegen.png")],
    ),
    "tools/slice_imagegen_reel_anticipation_beams.py": (
        themed("reel_anticipation_beam_"),
        [source("vslot_reel_anticipation_beams_imagegen.png")],
    ),
    "tools/slice_imagegen_result_modal_panels.py": (
        themed("result_modal_panel_", "_premium.webp"),
        [
            source(f"vslot_result_panel_{theme}_premium_imagegen.png")
            for theme in ("violet", "roman", "neon", "pharaoh", "ocean")
        ],
    ),
    "tools/slice_imagegen_settings_push_status_console.py": (
        [resource("settings_push_status_console.webp"), resource("settings_push_status_signal_pulse.webp")],
        [source("vslot_settings_push_status_console_imagegen.png")],
    ),
    "tools/slice_imagegen_slot_level_meter.py": (
        [resource("slot_level_session_panel.webp")],
        [source("vslot_slot_level_meter_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_ambient_overlays.py": (
        themed("theme_ambient_overlay_"),
        [source("vslot_theme_ambient_overlays_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_reel_glass_overlays.py": (
        themed("reel_glass_overlay_"),
        [source("vslot_theme_reel_glass_overlays_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_reel_spin_blur.py": (
        themed("reel_spin_blur_"),
        [source("vslot_theme_reel_spin_blur_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_reel_stop_flashes.py": (
        themed("reel_stop_flash_"),
        [source("vslot_theme_reel_stop_flashes_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_spin_energy_rims.py": (
        themed("reel_spin_energy_rim_"),
        [source("vslot_theme_spin_energy_rims_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_spin_overlays.py": (
        themed("theme_spin_overlay_"),
        [source("vslot_theme_spin_overlays_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_symbol_halos.py": (
        themed("symbol_bonus_scatter_halo_") + themed("symbol_win_halo_"),
        [source("vslot_theme_symbol_halos_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_win_bursts.py": (
        themed("theme_win_burst_"),
        [source("vslot_theme_win_bursts_imagegen.png")],
    ),
    "tools/slice_imagegen_theme_win_glow_sprites.py": (
        themed("win_glow_sprite_"),
        [source("vslot_theme_win_glow_sprites_imagegen.png")],
    ),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source_file:
        for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def path_record(path: str, kind: str | None = None) -> dict[str, str]:
    target = ROOT / path
    if not target.is_file():
        raise RuntimeError(f"Required imagegen derivation input is missing: {path}")
    record = {"path": path, "sha256": sha256(target)}
    if kind is not None:
        record["type"] = kind
    return record


def media_record(path: Path) -> dict[str, object]:
    with Image.open(path) as image:
        width, height = image.size
        mime_type = Image.MIME.get(image.format, "application/octet-stream")
    return {"type": mime_type, "width": width, "height": height}


def document() -> dict[str, object]:
    verify_asset_toolchain()
    entries = []
    seen_outputs: set[str] = set()
    for producer, (outputs, sources) in sorted(OUTPUT_GROUPS.items()):
        producer_path = ROOT / producer
        producer_text = producer_path.read_text(encoding="utf-8")
        if "verify_asset_toolchain()" not in producer_text or "NONCANONICAL_HISTORICAL_SLICER" in producer_text:
            raise RuntimeError(f"Canonical imagegen producer is not toolchain-gated: {producer}")
        producer_record = path_record(producer)
        source_records = [path_record(item) for item in sources]
        build_inputs = [path_record(path, kind) for kind, path in COMMON_BUILD_INPUTS]
        for output in sorted(outputs):
            if output in seen_outputs:
                raise RuntimeError(f"Duplicate canonical imagegen producer for {output}")
            seen_outputs.add(output)
            output_path = ROOT / output
            if not output_path.is_file():
                raise RuntimeError(f"Declared imagegen output is missing: {output}")
            output_sha256 = sha256(output_path)
            entries.append(
                {
                    "path": output,
                    "bytes": output_path.stat().st_size,
                    "sha256": output_sha256,
                    "media": media_record(output_path),
                    "origin": {
                        "class": "derived_imagegen",
                        "sources": source_records,
                        "producer": {**producer_record, "command": ["python3", producer]},
                    },
                    "build_inputs": build_inputs,
                    "reproduction": {"mode": "byte_exact", "expected_sha256": output_sha256},
                    "rights_review": {
                        "state": "review_required",
                        "evidence_refs": ["release_asset_rights_signoff_v1"],
                    },
                }
            )
    entries.sort(key=lambda entry: entry["path"])
    generator = Path(__file__)
    return {
        "schema_version": 1,
        "status": "derivation_integrity_not_legal_clearance",
        "generated_by": {
            "path": generator.relative_to(ROOT).as_posix(),
            "sha256": sha256(generator),
        },
        "toolchain": actual_versions(),
        "entries": entries,
    }


def encoded_document() -> str:
    return json.dumps(document(), ensure_ascii=True, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = encoded_document()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != expected:
            print("Imagegen derivation manifest is stale.")
            print("Run python3 tools/generate_imagegen_derivation_manifest.py")
            return 1
        print(f"Imagegen derivation manifest is current ({len(document()['entries'])} outputs).")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print(f"Wrote {OUTPUT.relative_to(ROOT)} with {len(document()['entries'])} outputs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
