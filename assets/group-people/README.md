# Neutral group people

Original Fairshare vectors. Each light/dark SVG shows 1–6 people; head circles and rounded shoulders use a shared 120 × 76 coordinate system. No gender, initials, skin colors or status colors are assigned.

`GroupPeopleIllustration.kt` renders the same SVG geometry with Compose Canvas on Android and iOS, avoiding a platform-specific SVG decoder. More than six members shows six silhouettes plus a `+N` label. Counts use active members only.

The illustration appears beside a single balance when there is spare width. Multiple currencies, long amounts, or enlarged type retain the entire header width. It is decorative; the toolbar members button remains the accessible navigation control. Light/dark selection follows the app appearance, independently of the group's accent.
