# ZeroIT website

Buildless, dependency-free product landing page with a local equal-split demo and preset FAQ helper. [Open the deployed website](https://zeroit.vercel.app). Deployment protection may require Vercel login.

Serve `dist/` with any static HTTP server. From the repository root:

```sh
python3 -m http.server 4173 --directory website/dist
```

The illustrated receipts are synthetic examples, not app screenshots. The demo parses integer cents and allocates remaining cents in participant order. FAQ answers run locally, use textContent for user input and send no messages to a server. Inputs disappear on reload. No AI, live support, analytics, accounts or payment integration is included.

The approved mark comes from branding/zeroit-mark.svg. The paper texture and playful illustrations are original HTML/CSS/SVG; no catalog component source was copied.

## Vercel hosting

GitHub main pushes automatically deploy website/dist to production at https://zeroit.vercel.app. The motion source revision 2a58170 was verified in a READY deployment with source=git and that exact commit SHA. Subsequent documentation-only revisions retain the same website assets. Hosted content was checked through authenticated access; deployment protection remains enabled and anonymous access is not asserted. The connected static project uses these settings:

| Setting | Value |
| --- | --- |
| Repository | Tirth-1999/ZeroIT |
| Production branch | main |
| Root Directory | website/dist |
| Framework Preset | Other |
| Build Command | Override enabled; empty |
| Output Directory | . |
| Dependency installation | None required |

These files can be served directly; do not run the mobile Gradle build for a website deployment. Configure the Root Directory in the Vercel project settings. See [Vercel static build settings](https://vercel.com/docs/builds/configure-a-build).

Keep deployment protection appropriate for the intended audience; an authenticated preview is not proof of public access. Never commit local .vercel, .openai, nested .git, credentials or deployment authentication material. No secrets or environment variables are required by the static site.

After deployment, check the page and all referenced asset URLs, responsive layouts, keyboard focus, reduced motion, odd-cent splits and the local FAQ. Record the exact source revision and deployment URL; website publication does not qualify a mobile store release.
