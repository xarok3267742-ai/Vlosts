# Noncanonical imagegen slicers

Status: provenance reference only. The scripts listed below are deliberately fail-closed and must not be used to overwrite reviewed application resources.

An isolated rerun on 2026-07-20 used Python 3.9.6, Pillow 11.3.0, FreeType 2.13.3, and libwebp 1.5.0. These 14 historical slicers produced pixel or geometry drift in 40 of their 42 outputs. The current packaged files remain authoritative checked-in masters and require per-file rights review; preserving a source image and historical crop algorithm does not make the current output byte-reproducible.

- `slice_imagegen_free_spins_stake_lock_overlay.py`
- `slice_imagegen_home_locked_slot_pulse.py`
- `slice_imagegen_home_slot_unlock_burst.py`
- `slice_imagegen_level_hud_assets.py`
- `slice_imagegen_privacy_web_panel.py`
- `slice_imagegen_push_permission_modal_panel.py`
- `slice_imagegen_reel_landing_spark.py`
- `slice_imagegen_settings_modal_panel.py`
- `slice_imagegen_settings_safety_anchor.py`
- `slice_imagegen_slam_stop_cue.py`
- `slice_imagegen_splash_ignition_overlay.py`
- `slice_imagegen_theme_reel_landing_sparks.py`
- `slice_imagegen_theme_slam_stop_cues.py`
- `slice_imagegen_top_bar.py`

Each file exits with `NONCANONICAL_HISTORICAL_SLICER` when invoked directly. `verifyStoreAssets` checks that this guard remains present. A future replacement must use a new producer, preserve the intended runtime geometry, pass emulator visual QA, and receive a new hash-bound derivation manifest before the guard is removed.
