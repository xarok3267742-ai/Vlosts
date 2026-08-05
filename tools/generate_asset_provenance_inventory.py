#!/usr/bin/env python3
"""Generate the deterministic media provenance inventory used by release review."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/legal/ASSET_PROVENANCE_INVENTORY.json"
RASTER_DERIVATION_MANIFEST = ROOT / "docs/legal/RASTER_DERIVATION_MANIFEST.json"
IMAGEGEN_DERIVATION_MANIFEST = ROOT / "docs/legal/IMAGEGEN_DERIVATION_MANIFEST.json"
MEDIA_SUFFIXES = {".png", ".webp", ".wav"}


def raster_derivations() -> dict[str, dict[str, object]]:
    document = json.loads(RASTER_DERIVATION_MANIFEST.read_text(encoding="utf-8"))
    return {entry["path"]: entry for entry in document["entries"]}


RASTER_DERIVATIONS = raster_derivations()


def imagegen_derivations() -> dict[str, dict[str, object]]:
    document = json.loads(IMAGEGEN_DERIVATION_MANIFEST.read_text(encoding="utf-8"))
    return {entry["path"]: entry for entry in document["entries"]}


IMAGEGEN_DERIVATIONS = imagegen_derivations()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def provenance(path: Path) -> dict[str, object]:
    relative = path.relative_to(ROOT).as_posix()
    derivation = RASTER_DERIVATIONS.get(relative) or IMAGEGEN_DERIVATIONS.get(relative)
    if derivation is not None:
        origin = derivation["origin"]
        producer = origin["producer"]["path"]
        manifest_path = (
            RASTER_DERIVATION_MANIFEST
            if relative in RASTER_DERIVATIONS
            else IMAGEGEN_DERIVATION_MANIFEST
        )
        source_paths = [producer, manifest_path.relative_to(ROOT).as_posix()]
        source_paths.extend(source["path"] for source in origin["sources"])
        source_paths.extend(build_input["path"] for build_input in derivation["build_inputs"])
        return {
            "media_role": "packaged_visual",
            "source_paths": sorted(set(source_paths)),
            "producer": producer,
            "reproducibility": "deterministic_generator",
            "rights_basis": (
                "generation_terms_and_owner_attestation_required"
                if origin["class"] == "derived_imagegen"
                else "project_procedural_source_and_owner_attestation_required"
            ),
        }
    if relative.startswith("qa/source/"):
        return {
            "media_role": "retained_imagegen_source_master",
            "source_paths": [relative],
            "producer": "openai_image_generation",
            "reproducibility": "authoritative_master",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative.startswith("app/src/main/res/raw/"):
        return {
            "media_role": "packaged_audio",
            "source_paths": ["tools/generate_slot_feedback_audio.py"],
            "producer": "tools/generate_slot_feedback_audio.py",
            "reproducibility": "deterministic_generator",
            "rights_basis": "project_procedural_source_and_owner_attestation_required",
        }
    if relative.startswith("docs/store/assets/screenshots/"):
        return {
            "media_role": "store_screenshot",
            "source_paths": [
                "tools/capture_play_store_screenshots.sh",
                "app/src/androidTest/java/com/vslot/app/MainActivitySmokeTest.kt",
                "docs/store/assets/screenshots/capture-metadata.json",
            ],
            "producer": "tools/capture_play_store_screenshots.sh",
            "reproducibility": "instrumentation_capture",
            "rights_basis": "application_ui_and_owner_attestation_required",
        }
    if relative == "docs/store/assets/v-slot-icon-master-v2.png":
        return {
            "media_role": "store_source_master",
            "source_paths": [relative],
            "producer": "openai_image_generation",
            "reproducibility": "authoritative_master",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative == "docs/store/assets/v-slot-icon-512-v2.png":
        return {
            "media_role": "store_icon_export",
            "source_paths": [
                "docs/store/assets/v-slot-icon-master-v2.png",
                "tools/export_store_graphics.py",
            ],
            "producer": "tools/export_store_graphics.py",
            "reproducibility": "deterministic_generator",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative == "docs/store/assets/v-slot-feature-graphic-master-v1.png":
        return {
            "media_role": "store_source_master",
            "source_paths": [relative],
            "producer": "openai_image_generation",
            "reproducibility": "authoritative_master",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative == "docs/store/assets/v-slot-feature-graphic-1024x500-v1.png":
        return {
            "media_role": "store_feature_graphic_export",
            "source_paths": [
                "docs/store/assets/v-slot-feature-graphic-master-v1.png",
                "tools/export_store_graphics.py",
            ],
            "producer": "tools/export_store_graphics.py",
            "reproducibility": "deterministic_generator",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative.endswith("app_logo_mark_v2.png"):
        return {
            "media_role": "packaged_visual",
            "source_paths": [
                "qa/source/vslot_app_logo_mark_chroma_imagegen.png",
                "qa/source/vslot_app_logo_mark_export.json",
                "tools/export_app_logo_mark.py",
            ],
            "producer": "tools/export_app_logo_mark.py",
            "reproducibility": "deterministic_generator",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    if relative.endswith(("app_icon_art_v2.png", "app_icon_foreground_v2.png")):
        return {
            "media_role": "packaged_visual",
            "source_paths": [
                "docs/store/assets/v-slot-icon-master-v2.png",
                "tools/export_store_graphics.py",
            ],
            "producer": "tools/export_store_graphics.py",
            "reproducibility": "deterministic_generator",
            "rights_basis": "generation_terms_and_owner_attestation_required",
        }
    return {
        "media_role": "packaged_visual",
        "source_paths": [relative],
        "producer": "authoritative_checked_in_master",
        "reproducibility": "authoritative_master",
        "rights_basis": "owner_attestation_and_per_asset_review_required",
    }


def media_files() -> list[Path]:
    roots = (
        ROOT / "app/src/main/res",
        ROOT / "qa/source",
        ROOT / "docs/store/assets",
    )
    return sorted(
        (
            path
            for media_root in roots
            for path in media_root.rglob("*")
            if path.is_file() and path.suffix.lower() in MEDIA_SUFFIXES
        ),
        key=lambda path: path.relative_to(ROOT).as_posix(),
    )


def document() -> dict[str, object]:
    entries = []
    for path in media_files():
        provenance_fields = provenance(path)
        entries.append(
            {
                "path": path.relative_to(ROOT).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
                **provenance_fields,
                "evidence_id": "release_asset_rights_signoff_v1",
            }
        )
    return {
        "schema_version": 1,
        "status": "inventory_only_not_legal_clearance",
        "generated_by": "tools/generate_asset_provenance_inventory.py",
        "entries": entries,
    }


def encoded_document() -> str:
    return json.dumps(document(), ensure_ascii=True, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail if the checked-in inventory is absent or stale.",
    )
    args = parser.parse_args()
    expected = encoded_document()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != expected:
            print(
                "Asset provenance inventory is stale. Run "
                "python3 tools/generate_asset_provenance_inventory.py"
            )
            return 1
        print(f"Asset provenance inventory is current ({len(document()['entries'])} files).")
        return 0

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print(f"Wrote {OUTPUT.relative_to(ROOT)} with {len(document()['entries'])} files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
