# Asset rights review: V Slot

Status: **release blocker until an authorized reviewer completes and signs the external evidence file**. This document and the generated inventory provide traceability; they are not a legal opinion and do not grant rights.

## Checked-in provenance

- `ASSET_PROVENANCE_INVENTORY.json` contains every packaged PNG, WebP, and WAV file, every `qa/source` imagegen master, and every Google Play graphic/screenshot. Each entry is bound to its path, byte length, SHA-256, and provenance class.
- `RASTER_DERIVATION_MANIFEST.json` binds 69 generated WebP files to their exact source hashes, producer command and hash, pinned raster toolchain, Noto Sans build input, and expected output hash. It proves derivation integrity, not legal clearance.
- `IMAGEGEN_DERIVATION_MANIFEST.json` binds 83 additional canonical WebP derivatives to retained imagegen masters, toolchain-gated slicers, and exact output hashes. The ten result banners remain in the raster manifest because they also use the pinned font input.
- `qa/source/` contains the retained imagegen masters used by the visual slicing pipeline. `tools/slice_imagegen_*.py`, `tools/generate_*.py`, and the notes under `qa/design/` document reproducible derivations for the covered asset families.
- `tools/generate_slot_feedback_audio.py` deterministically creates all six packaged WAV effects without third-party samples.
- `tools/fonts/noto-sans/` retains the exact Noto Sans variable font, upstream metadata, and complete SIL Open Font License 1.1 from immutable `google/fonts` revision `389b770410cc0b7c21c85673bfa2077420fe7f65`. Covered generators verify the font and raster toolchain hashes and have no system-font fallback.
- `NONCANONICAL_IMAGEGEN_SLICERS.md` identifies historical slicers that no longer reproduce the reviewed pixels. They are retained as source-trace evidence but are fail-closed so they cannot overwrite canonical resources.
- Google Play screenshots are unretouched instrumentation captures. Their APK and image hashes are separately pinned by `docs/store/assets/screenshots/capture-metadata.json`.
- Runtime dependency licenses and notices are handled separately by `THIRD_PARTY_NOTICES.md` and the release dependency-license gate.

## Known current gaps

- Many older WebP/PNG outputs are retained as authoritative checked-in masters rather than reproducible derivatives. The inventory says this explicitly and requires the same file as its source; an authorized reviewer must establish ownership for each such entry before approving `every_inventory_entry_reviewed`.
- A retained imagegen master does not by itself prove commercial rights. Preserve the applicable generation account/export record, prompt or request metadata when available, and the provider terms that applied at generation time in protected review evidence.
- Older assets outside `RASTER_DERIVATION_MANIFEST.json` and `IMAGEGEN_DERIVATION_MANIFEST.json` may contain rasterized text or historical visual processing whose original production inputs are not fully reproducible. They remain authoritative checked-in masters and require per-file ownership review. The current covered text generators use only the pinned OFL-licensed Noto Sans input.
- Product and theme names require a separate trademark/name check in every intended distribution market.

Regenerate and verify the inventory after any media change:

```bash
python3 tools/generate_asset_provenance_inventory.py
python3 tools/generate_asset_provenance_inventory.py --check
python3 tools/generate_raster_derivation_manifest.py --check
python3 tools/generate_imagegen_derivation_manifest.py --check
```

## Required manual review

An authorized reviewer must inspect the exact inventory used by the candidate release and confirm all of the following in a completed copy of `ASSET_RIGHTS_EVIDENCE_TEMPLATE.json`:

1. The image-generation account and applicable provider terms permit commercial distribution of every retained master and derivative.
2. Every packaged visual is mapped to a project-created source or generator; no unexplained third-party bitmap remains.
3. The six audio files are regenerated from the checked-in procedural generator and contain no sampled recording.
4. The app icon, feature graphic, screenshots, and listing copy are cleared for store use.
5. `V Slot`, `Фиолетовая Фортуна`, `Римские барабаны`, `Неоновые ночи`, `Золото фараона`, and `Океанская жемчужина` have received the required name/trademark clearance in intended distribution countries.
6. The pinned Noto Sans license and rasterized outputs are cleared, no unexplained third-party logo, character, artwork, font, or protected product presentation is present, and all notice obligations are satisfied.

Keep the completed evidence outside the repository in protected storage. The production build must receive its exact bytes and SHA-256 through environment-only inputs; changing `versionCode`, the release commit, or the checked-in inventory invalidates the sign-off.
