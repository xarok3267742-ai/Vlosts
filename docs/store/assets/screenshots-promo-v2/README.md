# V Slot Google Play promo screenshots v2

Five 1080 × 1920 RGB promotional screenshots built from real V Slot UI captures.

The CasiX Google Play gallery was used only as a hierarchy reference: bold two-line headlines, alternating color bands, and a framed app screen. No competitor image, logo, copy, or interface asset is embedded in these files.

## Exports

- `01-five-themes.png` — `ПЯТЬ ТЕМ / ОДНА ИГРА`
- `02-spin-and-lines.png` — `КРУТИ БАРАБАНЫ / ВЫБИРАЙ ЛИНИИ`
- `03-paytable.png` — `ВСЕ ВЫПЛАТЫ / ПОД РУКОЙ`
- `04-settings.png` — `НАСТРОЙ ИГРУ / ПОД СЕБЯ`
- `05-free-spins.png` — `ФРИСПИНЫ / БЕЗ ПОКУПОК`

All copy is rendered deterministically outside the real app screenshot frame. The generated bitmap is used only as the surrounding violet/gold/cyan background. Source and output SHA-256 values, the background prompt, dimensions, and provenance are recorded in `manifest.json`.

The exporter also rebuilds `../v-slot-google-play-screenshots-v2.zip` with fixed metadata and exact byte copies of the five PNG files, this manifest, and this README.

## Rebuild and verify

```bash
python3 tools/export_store_screenshot_promos.py
python3 tools/export_store_screenshot_promos.py --check
```

## Release note

This is the polished v2 presentation set. Before final Play Console upload, replace any older source capture with a fresh capture from the exact release-candidate QA payload and rerun the exporter and release screenshot validator.
