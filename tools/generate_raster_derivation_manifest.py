#!/usr/bin/env python3
"""Generate the byte-bound provenance manifest for deterministic raster outputs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image

from vslot_asset_fonts import FONT_PATH, FONT_SHA256, verify_font
from vslot_asset_toolchain import actual_versions, verify_asset_toolchain


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/legal/RASTER_DERIVATION_MANIFEST.json"
DRAWABLE = "app/src/main/res/drawable-nodpi"
DRAWABLE_LAND = "app/src/main/res/drawable-land-nodpi"
FONT_BUILD_INPUTS = (
    ("font", "tools/fonts/noto-sans/NotoSans[wdth,wght].ttf"),
    ("font_license", "tools/fonts/noto-sans/OFL.txt"),
    ("font_metadata", "tools/fonts/noto-sans/METADATA.pb"),
    ("font_loader", "tools/vslot_asset_fonts.py"),
)
TOOLCHAIN_BUILD_INPUTS = (
    ("toolchain_gate", "tools/vslot_asset_toolchain.py"),
    ("python_requirement", "tools/requirements-assets.txt"),
)
FONT_PRODUCERS = frozenset(
    {
        "tools/generate_analytics_consent_assets.py",
        "tools/generate_auto_spin_dialog_assets.py",
        "tools/generate_level_assets.py",
        "tools/generate_new_slot_assets.py",
        "tools/generate_paytable_copy_asset.py",
        "tools/generate_paytable_footer_assets.py",
        "tools/generate_third_party_notices_assets.py",
        "tools/slice_imagegen_theme_result_banners.py",
    }
)
ALL_THEME_SUFFIXES = ("", "roman", "neon", "pharaoh", "ocean")
DERIVED_THEME_SUFFIXES = ("neon", "pharaoh", "ocean")


def resource(name: str) -> str:
    return f"{DRAWABLE}/{name}"


def unsourced(paths: list[str]) -> list[tuple[str, tuple[str, ...]]]:
    return [(path, ()) for path in paths]


def themed_outputs(stem: str) -> list[str]:
    return [resource(f"{stem}_{suffix}.webp" if suffix else f"{stem}.webp") for suffix in ALL_THEME_SUFFIXES]


BUTTON_ASSETS = (
    ("spin_button_{suffix}_default", "spin_button_violet_default.webp"),
    ("spin_button_{suffix}_pressed", "spin_button_violet_pressed.webp"),
    ("spin_button_{suffix}_disabled", "spin_button_violet_disabled.webp"),
    ("spin_button_{suffix}_free_spins_default", "spin_button_violet_free_spins_default.png"),
    ("spin_button_{suffix}_free_spins_pressed", "spin_button_violet_free_spins_pressed.png"),
    ("spin_button_{suffix}_free_spins_disabled", "spin_button_violet_free_spins_disabled.png"),
    ("btn_autospin_{suffix}_default", "btn_autospin_default.webp"),
    ("btn_autospin_{suffix}_pressed", "btn_autospin_pressed.webp"),
    ("btn_autospin_{suffix}_disabled", "btn_autospin_disabled.webp"),
    ("btn_autospin_{suffix}_active", "btn_autospin_active.webp"),
    ("btn_autospin_{suffix}_active_pressed", "btn_autospin_active_pressed.webp"),
    ("btn_bet_minus_{suffix}", "btn_bet_minus.webp"),
    ("btn_bet_minus_{suffix}_pressed", "btn_bet_minus_pressed.webp"),
    ("btn_bet_minus_{suffix}_disabled", "btn_bet_minus_disabled.webp"),
    ("btn_bet_plus_{suffix}", "btn_bet_plus.webp"),
    ("btn_bet_plus_{suffix}_pressed", "btn_bet_plus_pressed.webp"),
    ("btn_bet_plus_{suffix}_disabled", "btn_bet_plus_disabled.webp"),
    ("btn_max_lines_{suffix}_default", "btn_max_lines_default.webp"),
    ("btn_max_lines_{suffix}_pressed", "btn_max_lines_pressed.webp"),
    ("btn_max_lines_{suffix}_disabled", "btn_max_lines_disabled.webp"),
    ("paytable_button_{suffix}", "paytable_button.webp"),
    ("label_paytable_button_{suffix}", "label_paytable_button.webp"),
    ("auto_spin_active_halo_{suffix}", "auto_spin_active_halo.webp"),
)
CHROME_ASSETS = (
    ("slot_machine_frame", "slot_machine_frame_violet.webp"),
    ("slot_marquee_glass", "slot_marquee_glass.webp"),
    ("slot_cabinet_lights", "slot_cabinet_lights.webp"),
    ("slot_cabinet_chase_lights", "slot_cabinet_chase_lights.webp"),
    ("reel_depth_dividers", "reel_depth_dividers.webp"),
    ("reel_window_depth_mask", "reel_window_depth_mask.webp"),
    ("free_spins_mode_overlay", "free_spins_mode_overlay_violet.webp"),
    ("slot_spin_deck_glow", "slot_spin_deck_glow_violet.webp"),
    ("spin_button_ready_glow", "spin_button_ready_glow_violet.webp"),
    ("slot_paytable_dock_glow", "slot_paytable_dock_glow_violet.webp"),
    ("slot_control_console_backplane", "slot_control_console_backplane_violet.webp"),
    ("bet_panel", "bet_panel.webp"),
    ("slot_control_meter_glow", "slot_control_meter_glow.webp"),
    ("active_lines_badge", "active_lines_badge.webp"),
    ("free_spins_badge", "free_spins_badge.webp"),
    ("reel_cell_backdrop", "reel_cell_backdrop.webp"),
)
LABEL_ASSETS = (
    ("label_bet", "label_bet.webp"),
    ("label_lines", "label_lines.webp"),
    ("label_total_bet", "label_total_bet.webp"),
    ("label_last_win", "label_last_win.webp"),
)
BYTE_EXACT_SPIN_BLUR_SYMBOLS = (
    "nn_symbol_bar",
    "nn_symbol_cherry",
    "nn_symbol_credit",
    "nn_symbol_crown",
    "nn_symbol_star",
    "nn_symbol_v_wild",
    "op_symbol_anchor",
    "op_symbol_coin",
    "op_symbol_crown",
    "op_symbol_pearl",
    "op_symbol_shell",
    "op_symbol_starfish",
    "op_symbol_trident",
    "pg_symbol_ankh",
    "pg_symbol_coin",
    "pg_symbol_crown",
    "pg_symbol_lotus",
    "pg_symbol_scarab",
    "pg_symbol_sun",
    "pg_symbol_tablet",
    "rr_symbol_coin",
    "rr_symbol_column",
    "rr_symbol_crown",
    "rr_symbol_gem",
    "rr_symbol_laurel",
    "rr_symbol_lightning",
    "rr_symbol_shield",
    "rr_symbol_v_wild",
    "vf_symbol_bar",
    "vf_symbol_cherry",
    "vf_symbol_coin",
    "vf_symbol_crown",
    "vf_symbol_diamond",
    "vf_symbol_ruby",
    "vf_symbol_star",
    "vf_symbol_v_wild",
)
BYTE_EXACT_PAYTABLE_ASSETS = (
    ("paytable_odds_header_glow", "paytable_odds_header_glow.webp"),
    ("paytable_payline_guide", "paytable_payline_guide.webp"),
    ("paytable_row_panel", "paytable_row_panel.webp"),
)
BYTE_EXACT_RESULT_ASSETS = (
    ("result_free_spins_award_panel", "result_free_spins_award_panel.webp"),
    ("result_stage_lattice", "result_stage_lattice.webp"),
    ("result_win_payout_burst", "result_win_payout_burst.webp"),
)
RESULT_THEME_SUFFIXES = ("roman", "neon", "pharaoh", "ocean")


OUTPUT_GROUPS: dict[str, tuple[list[tuple[str, tuple[str, ...]]], str]] = {
    "tools/generate_analytics_consent_assets.py": (
        unsourced([
            resource("title_analytics_consent.webp"),
            resource("body_analytics_consent.webp"),
            resource("label_analytics_decline.webp"),
            resource("label_analytics_allow.webp"),
            resource("settings_analytics_on.webp"),
            resource("settings_analytics_off.webp"),
        ]),
        "procedural",
    ),
    "tools/generate_level_assets.py": (
        unsourced([
            resource("level_progress_panel.webp"),
            resource("level_progress_fill.webp"),
            resource("level_progress_track_glow.webp"),
            resource("level_progress_milestones.webp"),
            resource("level_progress_cap.webp"),
            resource("level_progress_pulse.webp"),
            resource("home_xp_readout_plate.webp"),
        ]),
        "procedural",
    ),
    "tools/generate_new_slot_assets.py": (
        unsourced([
            *[
                resource(f"{prefix}_symbol_{symbol}.webp")
                for prefix, symbols in (
                    ("nn", ("v_wild", "holo_chip", "neon_seven", "credit", "crown", "star", "cherry", "bar")),
                    ("pg", ("v_wild", "scarab", "ankh", "coin", "crown", "sun", "lotus", "tablet")),
                    ("op", ("v_wild", "pearl", "trident", "coin", "crown", "starfish", "shell", "anchor")),
                )
                for symbol in symbols
            ],
            *[
                resource(f"slot_card_{theme}_{state}.webp")
                for theme in ("neon_nights", "pharaoh_gold", "ocean_pearl")
                for state in ("default", "pressed")
            ],
        ]),
        "procedural",
    ),
    "tools/generate_auto_spin_dialog_assets.py": (
        unsourced([
            resource("title_auto_spin.webp"),
            resource("label_auto_spin_choose.webp"),
            resource("label_auto_spin_stop.webp"),
        ]),
        "procedural",
    ),
    "tools/generate_paytable_copy_asset.py": (
        unsourced([
            resource("label_paytable_bet_explanation.webp"),
            f"{DRAWABLE_LAND}/label_paytable_bet_explanation.webp",
        ]),
        "procedural",
    ),
    "tools/generate_paytable_footer_assets.py": (
        unsourced([
            *[
                resource(f"label_paytable_footer_{theme}.webp")
                for theme in ("violet", "roman", "nn", "pg", "op")
            ],
            *[
                f"{DRAWABLE_LAND}/label_paytable_footer_{theme}.webp"
                for theme in ("violet", "roman", "nn", "pg", "op")
            ],
        ]),
        "procedural",
    ),
    "tools/generate_third_party_notices_assets.py": (
        unsourced([resource("label_third_party_notices.webp")]),
        "procedural",
    ),
    "tools/slice_imagegen_theme_result_banners.py": (
        [
            (
                resource(f"slot_{kind}_banner_{theme}.webp"),
                ("qa/source/vslot_theme_result_banner_backgrounds_imagegen.png",),
            )
            for kind in ("big_win", "bonus_free_spins")
            for theme in ("violet", "roman", "neon", "pharaoh", "ocean")
        ],
        "derived_imagegen",
    ),
    "tools/generate_free_spins_charge_assets.py": (
        unsourced(themed_outputs("free_spins_rail_charge")),
        "procedural",
    ),
    "tools/generate_home_scroll_cue_assets.py": (
        unsourced([
            resource("home_scroll_bottom_veil.webp"),
            resource("home_scroll_right_veil.webp"),
        ]),
        "procedural",
    ),
    "tools/generate_paytable_bonus_lane_assets.py": (
        unsourced(themed_outputs("paytable_bonus_lane")),
        "procedural",
    ),
    "tools/generate_reel_aperture_assets.py": (
        unsourced(themed_outputs("reel_aperture_shadow")),
        "procedural",
    ),
    "tools/generate_reel_brake_assets.py": (
        unsourced(themed_outputs("reel_brake_clamp")),
        "procedural",
    ),
    "tools/generate_reel_motion_streak_assets.py": (
        unsourced(themed_outputs("reel_motion_streak")),
        "procedural",
    ),
    "tools/generate_settings_feedback_icons.py": (
        unsourced([
            resource("settings_sound_on.webp"),
            resource("settings_sound_off.webp"),
            resource("settings_haptics_on.webp"),
            resource("settings_haptics_off.webp"),
        ]),
        "procedural",
    ),
    "tools/generate_slot_backgrounds.py": (
        unsourced([resource("nn_bg.webp"), resource("pg_bg.webp"), resource("op_bg.webp")]),
        "procedural",
    ),
    "tools/generate_slot_symbol_spin_blur_assets.py": (
        [
            (
                resource(f"{symbol}_spin_blur.webp"),
                (resource(f"{symbol}.webp"),),
            )
            for symbol in BYTE_EXACT_SPIN_BLUR_SYMBOLS
        ],
        "procedural",
    ),
    "tools/generate_theme_paytable_assets.py": (
        [
            (
                resource(f"{output_stem}_{theme}.webp"),
                (resource(source_name),),
            )
            for theme in DERIVED_THEME_SUFFIXES
            for output_stem, source_name in BYTE_EXACT_PAYTABLE_ASSETS
        ],
        "procedural",
    ),
    "tools/generate_theme_result_assets.py": (
        [
            (
                resource(f"{output_stem}_{theme}.webp"),
                (resource(source_name),),
            )
            for theme in RESULT_THEME_SUFFIXES
            for output_stem, source_name in BYTE_EXACT_RESULT_ASSETS
        ],
        "procedural",
    ),
    "tools/generate_spin_impact_flash_assets.py": (
        unsourced(themed_outputs("spin_impact_flash")),
        "procedural",
    ),
    "tools/generate_theme_paylines.py": (
        [
            *[
                (
                    resource(f"payline_markers_overlay_{theme}_active_{index}.webp"),
                    (resource(f"payline_markers_overlay_active_{index}.webp"),),
                )
                for theme in DERIVED_THEME_SUFFIXES
                for index in range(1, 11)
            ],
            *[
                (
                    resource(f"payline_win_{theme}_{index}.webp"),
                    (resource(f"payline_win_{index}.webp"),),
                )
                for theme in DERIVED_THEME_SUFFIXES
                for index in range(1, 11)
            ],
        ],
        "procedural",
    ),
    "tools/generate_theme_slot_buttons.py": (
        [
            (
                resource(f"{output_template.format(suffix=theme)}.webp"),
                (resource(source_name),),
            )
            for theme in DERIVED_THEME_SUFFIXES
            for output_template, source_name in BUTTON_ASSETS
        ],
        "procedural",
    ),
    "tools/generate_theme_slot_chrome.py": (
        [
            (resource(f"{output_stem}_{theme}.webp"), (resource(source_name),))
            for theme in DERIVED_THEME_SUFFIXES
            for output_stem, source_name in CHROME_ASSETS
        ],
        "procedural",
    ),
    "tools/generate_theme_slot_labels.py": (
        [
            (resource(f"{output_stem}_{theme}.webp"), (resource(source_name),))
            for theme in DERIVED_THEME_SUFFIXES
            for output_stem, source_name in LABEL_ASSETS
        ],
        "procedural",
    ),
    "tools/generate_total_bet_link_assets.py": (
        unsourced(themed_outputs("total_bet_link_pulse")),
        "procedural",
    ),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def path_record(path: str, kind: str | None = None) -> dict[str, str]:
    target = ROOT / path
    if not target.is_file():
        raise RuntimeError(f"Required derivation input is missing: {path}")
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
    verify_font()
    entries = []
    seen_outputs: set[str] = set()
    for producer, (outputs, origin_class) in sorted(OUTPUT_GROUPS.items()):
        producer_record = path_record(producer)
        build_input_specs = TOOLCHAIN_BUILD_INPUTS
        if producer in FONT_PRODUCERS:
            build_input_specs = FONT_BUILD_INPUTS + build_input_specs
        build_inputs = [path_record(path, kind) for kind, path in build_input_specs]
        for output, sources in sorted(outputs):
            if output in seen_outputs:
                raise RuntimeError(f"Duplicate canonical raster producer for {output}")
            seen_outputs.add(output)
            source_records = [path_record(source) for source in sources]
            output_path = ROOT / output
            if not output_path.is_file():
                raise RuntimeError(f"Declared raster output is missing: {output}")
            output_sha256 = sha256(output_path)
            entries.append(
                {
                    "path": output,
                    "bytes": output_path.stat().st_size,
                    "sha256": output_sha256,
                    "media": media_record(output_path),
                    "origin": {
                        "class": origin_class,
                        "sources": source_records,
                        "producer": {
                            **producer_record,
                            "command": ["python3", producer],
                        },
                    },
                    "build_inputs": build_inputs,
                    "reproduction": {
                        "mode": "byte_exact",
                        "expected_sha256": output_sha256,
                    },
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
        "font": {
            "path": FONT_PATH.relative_to(ROOT).as_posix(),
            "sha256": FONT_SHA256,
            "license": "OFL-1.1",
            "upstream_commit": "389b770410cc0b7c21c85673bfa2077420fe7f65",
        },
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
            print("Raster derivation manifest is stale.")
            print("Run python3 tools/generate_raster_derivation_manifest.py")
            return 1
        print(f"Raster derivation manifest is current ({len(document()['entries'])} outputs).")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print(f"Wrote {OUTPUT.relative_to(ROOT)} with {len(document()['entries'])} outputs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
