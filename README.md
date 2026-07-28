# Shop Level

A precision shop instrument for your phone — installable PWA, works offline.

**Tools:** bubble level & angle finder (with true-zero flip calibration, hold/zero, wake lock, haptics) · calibrated on-screen ruler · inch-fraction calculator with miter helper · tap drill / drill size / fraction reference charts.

**Install:** open the GitHub Pages URL in Chrome on your phone → ⋮ → *Add to Home screen*. Launches fullscreen with its own icon and keeps working with no signal.

**Calibrate for real precision:**
1. *Level:* CAL → set phone on any still surface → capture → rotate phone 180° → capture. Do once flat, once on-edge. Cancels sensor bias; typical accuracy ±0.1–0.2° after calibration.
2. *Ruler:* CALIBRATE → match the outline to a bank card.

Single-file app (`index.html`) — no build step, no dependencies.

## Silage Loads (`silage/`)

Separate app, own home-screen icon: tally counter for hauling. One big button per load, shows today / yesterday / job total, dash clock with keep-screen-on, per-day history with fix-up +/− buttons, and a **New Job** button that starts a fresh tracker for each field while keeping the old ones. Counts save on the phone (localStorage), works offline once visited. Install from `<pages-url>/silage/`.
