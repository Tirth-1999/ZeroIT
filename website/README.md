# ZeroIT website

The accepted ZeroIT website lives in `website/dist` and is hosted at https://zeroit.vercel.app/.

Serve it locally with `python3 -m http.server 4173 --directory website/dist`. Vercel uses root `website/dist`, framework Other, no install/build command, output `.`.

The design uses original receipt illustrations, colorful chapters, a curved SVG ribbon and a continuous scroll story. The star travels through the page; a rolling ball lifts the closing letters before the wordmark settles onto the footer line. Reduced motion and unavailable animation libraries preserve readable static content. GSAP 3.15.0 and ScrollTrigger retain their vendor notices in `dist/vendor/NOTICE.txt` and are not relicensed under ZeroIT's own license.

The equal-split demo uses exact integer cents. The FAQ uses browser-local preset replies, not AI or live support. Inputs are not transmitted or retained across reloads. Receipts are synthetic illustrations; public mobile store downloads, accounts and cloud sync are not claimed to be available.

The previous landing-page design is retired; Git history preserves its earlier versions. `website-next` only forwards the former preview address to the main site; it contains no duplicate implementation. Future website work belongs here.
