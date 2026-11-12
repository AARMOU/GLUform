# GLUform UI audit and Fold rotation test plan

Review date: 2026-11-11. Source baseline: `5b88de021f1ec4cf00b4b0e15d18906fa31c52e6`.
Scope: first-pass review based on the owner's Samsung Fold screenshots and the GLUform 0.1.0 source. This is an audit and test plan, **not** an assertion that on-device tests have passed.

## P0 — Fold rotation / viewport preservation

**Observed report:** Rotating the Fold can leave the CAD viewport as a narrow strip.

**Confirmed source-level mechanism:** `ui/Screen.kt` used `compact = maxWidth < 720.dp`. When the view was at least 720dp wide, a 250dp left browser (if open) and a 300dp right inspector were docked beside the canvas. If both were present, an 800dp window left only 250dp for the viewport.

**Change in this branch:** `ViewportLayout.kt` centralizes the sidebar widths and enforces at least 560dp for a docked canvas. Windows narrower than 1110dp use the compact/overlay layout instead. The renderer, camera, picking, and CAD engine are unchanged. Width-policy regression tests cover Fold-like widths and the threshold.

**Limitations:** This is a strong source-level diagnosis, not yet a verified device fix. A narrow or distorted GL surface after the layout correction would require inspecting Compose layout bounds versus `GLSurfaceView.onSurfaceChanged` sizes and whether orientation configuration changes are delivered. The compact inspector can still overlay part of the viewport.

## UI changes to prioritize after the rotation fix

| Priority | Issue | Recommendation |
|---|---|---|
| P0 | Modeling canvas loses space to panels | Viewport-first adaptive layout; collapsible or overlaid sidebars |
| P0 | Rotation/posture behavior unverified | Test folded/open states, portrait/landscape, resize, system bars, keyboard |
| P1 | Very long horizontal modeling ribbon | Organize into Sketch, Solid, Modify, Inspect; preserve active tool visibility |
| P1 | Sketch button looks active when not sketching | Separate primary action appearance from selected/active state |
| P1 | Small timeline chips and lengthy contextual menu | Larger touch targets, clearer feature icons, short edit path |
| P1 | Measurement inspector occupies canvas | Collapsible bottom sheet with compact summary and drag handle |
| P1 | View controls hidden in menu | On-canvas navigation cube, Home, Fit, orthographic/perspective toggle |
| P1 | High-contrast grid competes with geometry | Reduce minor grid prominence; emphasize major axes and origin |
| P1 | Browser open state consumes compact viewport | Allow independent browser state and easy dismissal |
| P2 | Save/dirty indication is subtle | Clear file state and save feedback |
| P2 | Complex commands accessible only through JSON | Typed tools with contextual selection and validation |
| P2 | Touch/stylus ergonomics inconsistent | 48dp minimum targets; S Pen and gesture evaluation |

## Acceptance tests before calling the rotation bug fixed

1. Open the browser and Measure inspector simultaneously on the Fold.
2. Rotate portrait ↔ landscape several times, open and close the Fold, and resize/multitask where available.
3. Verify the viewport never becomes a narrow strip and there are no black bars, clipping, or stretched geometry.
4. Verify orbit, pan, pinch zoom, face picking and sketch-point placement after each rotation.
5. Verify sketch, extrude, fillet and parameter-edit regeneration still work.
6. On a wide tablet/desktop-sized Android window (>=1110dp), verify both docked panels leave >=560dp for the viewport.
7. Run Android unit tests and build; test on a real Fold before closing the report.

## Validation status

- On-branch source changes: committed to `gluese/fold-rotation-viewport-20261111`.
- Width-policy source: compiles under standalone Kotlin; nine representative width cases pass.
- Android Gradle / JNI compilation: **not verified here**.
- Physical device rotation, OpenGL surface handling and interaction: **not verified here**.
- Deployment/merge: **not performed**.

Keep the layout bug fix separate from the broader UI redesign so later regressions can be isolated.
