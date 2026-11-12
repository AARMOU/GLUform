# GLUform touch-first UI overhaul — implementation and QA

Branch: `gluese/touch-ui-overhaul-20261111` (based on `gluese/fold-rotation-viewport-20261111`). Never merge without owner approval.

## Implemented

- **Tool organization:** The original full-width, endless command ribbon is separated into `Create`, `Modify` and `Construct` tabs. All existing modeling tools remain accessible in their original groups. Search retains access to engine command palette. Active tools show selection styling independently of the Sketch command.
- **Viewport navigation:** A compact on-canvas view pad provides Top, Front, Right, Home/3D, Fit and Perspective/Orthographic. Camera commands use existing camera APIs, not new CAD-engine functions.
- **Compact inspector:** Bottom inspector starts as a small summary and expands on demand. Starting an active modeling form or sketch opens the inspector automatically. The user can collapse it without cancelling a tool.
- **Browser:** In compact layout the browser drawer has an explicit Close control. Its touch targets are enlarged.
- **Timeline:** Feature chips are at least 48dp tall; tap edits a feature immediately (or opens a sketch); long-press opens the existing command menu for rename, suppress, roll back and delete.
- **Grid:** The OpenGL draw path now enables alpha blending for its grid pass, making the intended minor/major opacity meaningful, and reduces both opacities further. The axes remain distinct.
- **Touch ergonomics:** Toolbar buttons, inspector pick slots, filter/assist chips and key form actions have minimum 48dp touch heights.
- **File feedback:** Save is an explicit toolbar action; a dirty marker and document units appear alongside the document name.
- **Regression checks:** Tool groups remain exhaustive, and the camera views exposed by the pad exist. The previous Fold viewport width tests continue to run.

## Deferred / not claimed

- This is a native UI revision, not a CAD engine or geometry fix.
- The on-canvas view pad is not a fully animated 3D navigation cube. It provides the requested *function* (direct views) with less gesture ambiguity on touch.
- Advanced SolidCraft commands outside the guided `Tools.all` remain accessible through the command palette JSON interface. Dedicated sheet-metal and assembly dialogs would require separate feature contracts, tests and interaction flows.
- Actual usability, visual layout and orientation behavior must be verified on the Fold; source code and CI cannot replace touch testing.

## Device acceptance checklist

1. Open a design with Sketch → Extrude → Fillet and rotate open/closed Fold portrait/landscape.
2. Check that the viewport remains wide, the navigation pad works, the grid doesn't overpower the model, and the browser closes.
3. Switch Create/Modify/Construct, confirm expected commands, and use Search tools.
4. Enter/finish a sketch; verify its sketch ribbon and the inspector remain usable.
5. Tap a feature to edit; long-press it to rename/suppress and verify tool behavior.
6. Use Measure, collapse/expand details, and pick faces/edges without accidental gesture interference.
7. Save an edited design; verify save marker and unit label, re-open and inspect.
8. Test touch, stylus, camera gestures, and tight split-screen widths.

## CI

The branch-only GitHub Actions job builds a debug-signed ARM64 APK with SolidCraft 0.40.0, runs layout and UI contract tests, and verifies that the package contains `libcadbridge.so`. No Play Store signing or merges.
