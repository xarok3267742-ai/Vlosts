# Privacy Loading Sweep

Source imagegen asset: `qa/source/vslot_privacy_loading_sweep_imagegen.png`

Runtime asset: `app/src/main/res/drawable-nodpi/privacy_loading_sweep.webp`

Visual QA: `qa/screenshots/privacy_loading_sweep_contact_sheet.png`

The sweep is a decorative security scan layer for the Privacy WebView loading state. It sits behind the existing shield and scan rail, uses low settled alpha, and has a finite entrance animation only.

Compliance notes:
- No money, payout, prize, casino-link, or gambling-callout imagery.
- No visible text, letters, numbers, logos, or watermarks.
- Accessibility stays on the loading container; the sweep itself is decorative.
- SSL/network/privacy behavior is unchanged by this visual layer.
