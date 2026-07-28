# Privacy Loading Overlay Visual Philosophy

Generated on 2026-07-07 with the project imagegen pipeline.

Source and QA evidence:
- `qa/source/vslot_privacy_loading_overlay_imagegen.png`
- `app/src/main/res/drawable-nodpi/privacy_loading_shield.webp`
- `app/src/main/res/drawable-nodpi/privacy_loading_scan_rail.webp`
- `qa/screenshots/privacy_loading_overlay_contact_sheet.png`

Design intent:
- Privacy policy loading should feel like a protected document state inside the V Slot cabinet, not a blank WebView.
- The visible loading art contains no text, letters, money, coins, prizes, trophies, or cashout cues.
- The document is deliberately blank; all accessibility copy remains controlled by Russian string resources.
- The loading overlay is shown only while a validated policy URL is loading and is hidden on success or error.

Implementation notes:
- Portrait and landscape layouts use the same shield and scan rail WebP assets with orientation-specific dimensions.
- `PrivacyFragment` uses a finite loading polish animation, not an infinite loop.
- Error states remain above loading and keep the existing retry/error image flow.
