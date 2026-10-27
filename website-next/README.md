# ZeroIT — new design study

A separate, original presentation prototype for ZeroIT. The existing `website/` remains the current site; this directory does not replace it.

[Open the separate design preview](https://zeroit-next.vercel.app). The new zeroit-next Vercel project is READY with source revision `8132af02b37d23c3343851e235f241ce23051703`; its API target is production within that separate project. It does not replace the existing ZeroIT site or its alias. Hosted HTML, scripts and CSS were checked through authenticated access. Default deployment protection remains enabled, so Vercel login may be required; anonymous access is not asserted. This documentation update leaves the preview source assets unchanged.

Serve `dist/` as static files. From the repository root:

```sh
python3 -m http.server 4174 --directory website-next/dist
```

For a separate Vercel project use root `website-next/dist`, framework Other, no install or build command, output `.`. Do not reassign the current site's domain or change its project root.

This study uses oversized editorial typography, original receipt illustrations, color chapters and a curved SVG ribbon. Its cubic path geometry bends with scrolling; the text follows that path. Native scrolling remains in control. Reduced motion and unavailable animation libraries preserve a static curved ribbon and readable content. GSAP 3.15.0 and ScrollTrigger are bundled locally with their original notices in `dist/vendor/NOTICE.txt`; these vendor files are not relicensed under ZeroIT's own license.

The equal-split demo uses integer cents, validates monetary precision, and assigns remaining cents in participant order. The FAQ uses preset browser-local replies, not AI or live support. Demo inputs/messages are not transmitted or retained across reloads. Illustrative receipts are synthetic, not app screenshots. Current app availability and unimplemented sync are stated on the page.

The design draws broad motion and presentation inspiration from Hyperspeed; its source, logo, imagery and copy have not been imported. ZeroIT retains its own approved mark and identity. This prototype is a separate design proposal, not user acceptance of a replacement or a claim of mobile product changes.
