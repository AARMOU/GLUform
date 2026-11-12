# GLUform Product Vision & Engineering Charter

> Owner-defined vision, November 11, 2026 (Mountain). This document is planning authority, **not** authorization to start held features, merge PRs, publish builds, or modify Arcana operations.

## 1. Product north star

**GLUform is a fully featured, manufacturing-precision CAD environment built for Android phones, with a radically compact, adaptive, touch-native interface.** Its primary users are makers and hobbyists, not just professional CAD operators. Comprehensive mechanical, parametric, assembly, and surface/organic workflows must be achievable without desktop-style panel clutter or sacrificing capability.

**Long-term ambition:** A user can describe an object in natural language or provide a blueprint, and GLUform interprets the intent, constructs an editable parametric 3D model, and shows progressive real-time changes as it works. Every AI-produced operation remains inspectable, editable and reversible.

This is a technical ambition, not an assertion that arbitrary blueprints can already be converted accurately or that every model can be generated at interactive speed.

### Owner-confirmed decisions

| Area | Direction |
|---|---|
| Audience | Makers and hobbyists |
| Product ambition | Full-featured mobile CAD |
| Modeling scope | Comprehensive CAD: mechanical, assemblies and organic/surface capabilities over time |
| Interaction | Customizable modes: gesture/direct-touch and precise parametric controls |
| Accuracy | Manufacturing precision; geometry must remain measurable and editable |
| UI | Progressive disclosure; **smallest practical footprint without losing functions** |
| Visual/image workflow | Long-term image or blueprint to 3D |
| Platforms | Android phones first; folded, unfolded, portrait and landscape form factors |
| Immediate priority | Polish, performance and stability |
| AI | Long-term AI design generation |
| Distribution | Public Google Play Store |
| Worker autonomy | Workers may code, open PRs, run tests and build test APKs; **never merge or release without owner approval** |
| Automation | Audit scheduling capacity before any reallocation; leave Arcana intact |
| Interaction experiments | Context-sensitive menus/buttons, tap-and-hold, gesture-driven actions and continuous drag-to-trace spline |

## 2. Interaction design principles — product contract

1. **Canvas/viewport first.** Geometry gets screen space; auxiliary controls appear near the relevant entity, not as permanently docked giant forms.
2. **Direct manipulation plus precision.** Touch drags, axis/plane handles and menus coexist with exact dimensions, constraints, snapping and editable parameters. The interaction mode is user-selectable, not forced.
3. **Adaptive instead of overwhelming.** Current selection, active feature, document state, and available actions determine which controls appear. Hide unrelated options but always provide discovery/search.
4. **Gesture vocabulary is explicit and reversible.** Tap selects; long-press opens local context; drag performs the currently declared action; two-finger gestures manipulate the camera; every transform offers preview, cancel and undo. Avoid collisions with Android system gestures.
5. **Novel interfaces must be testable.** Propose contextual tool belts, radial menus, adaptable action chips, miniature inspectors, one-handed modes, spatial/edge gestures and pressure-aware stylus enhancements, but prototype and validate rather than shipping novelty for its own sake.
6. **No functionality sacrificed to minimalism.** Every advanced operation stays discoverable in a search/palette/inspector path even if hidden by default.
7. **Accessible and phone-first.** Maintain touch target guidelines, left/right-handed layouts, readable contrast, gesture alternatives, and stable behavior through rotation and fold transitions.

### Drag-to-trace spline — owner-requested concept (design work, not implementation)

Build a **continuous finger-drawn spline**: while the finger follows a contour, the tool automatically captures a bounded sequence of candidate fit/control points and previews a smooth curve live. The feature should:
- Use distance/curvature-adaptive samples with jitter filtering, not one uncontrolled point per touch event.
- Let users switch between freehand fitting, explicit control-point mode and precise dimensions.
- Preserve corners intentionally and make node reduction/fit tolerance adjustable.
- Offer line/arc inference, grid/geometry snapping, visible live preview, pause/resume, undo-last-segment, and commit/cancel.
- Allow optional imported-image tracing in a later phase **without** beginning held Canvas Import.
- Measure max deviation, control-point count, latency, accidental-point rate and reproducible sketch constraints on physical Android devices.
- Define gesture tests for interrupted touch, multitouch, rotate, stylus, palm rejection where supported.

This concept is a product requirement; its exact algorithm and implementation require design validation before approval.

## 3. Engineering strategy

- Keep **SolidCraft** as a serious geometry and command engine while proving the JNI integration, renderer and Android command coverage. Treat engine versions as pinned, upgraded through CI/test gates.
- Introduce a typed **CAD action/intent layer** between UI, future AI and engine commands: preconditions, typed parameters, previews, deterministic execution, geometry/constraint validation, undo/redo and telemetry. Both human and AI actions should use the same authoritative path.
- Implement selection-aware viewport affordances and input-state ownership to avoid conflicting interactions. Favor small, independently testable changes with Android instrumentation and unit tests.
- Do not equate a generated mesh with manufacturing-ready CAD. Preserve solid topology, units, constraints, feature history and export checks; validate against known dimensions, geometric tolerances and problematic features.
- Keep the modeling core usable without AI. AI integration must not make ordinary CAD depend on a remote service.
- Store image/blueprint source, interpretation confidence and provenance when image flows are eventually approved; require the user to confirm uncertain dimensions, holes and hidden features.

## 4. Phased roadmap and gates

### P0 — Trust the foundation (FIRST)

**Status: PLANNING / read-only baseline authorized; no automatic implementation start.**

- Reconcile PR #1 Fold rotation and PR #2 touch UI branch/CI and real device acceptance before integration.
- Establish reproducible Android phone benchmarks for cold start, viewport frame times, gesture latency, scene update time, memory, autosave/recovery and build reproducibility.
- Reproduce and classify crashes, GL surface loss, layout clipping, command UI dead ends, long-press/touch conflicts, failed sketch updates, and stale renderer/native state.
- Add automated unit/instrumentation/smoke gates once their scope is approved; artifact integrity and signed debug build provenance must be checked.
- Run a benchmark matrix: empty design; ordinary sketch→extrude→fillet→edit; split bodies; hundreds/thousands of edges; open/closed Fold, orientations, split-screen; low memory/background/resume.
- Provisional targets (to revise using measurements, **not proven performance**): responsive manipulation aiming for 60fps on typical models, graceful degradation above that complexity, no lost model state after configuration changes, and regression-free export/reopen round trips.

### P1 — Interaction system and core workflow

- Prototype contextual tool placement; guided feature editors instead of oversized always-open forms.
- Maintain comprehensive access through a searchable command center; select gesture and parametric modes per tool or user preference.
- Implement the drag-to-trace spline **only after a separately approved scoped work order and test plan**.
- Improve dimensioning, snapping, sketch constraint workflows, timeline context and multi-body selection with performance evidence.
- **HOLD (owner explicitly said do not start):** Reference-image/Canvas Import.
- **HOLD (owner explicitly said do not start):** Interactive Move/Copy/Rotate gizmo. Vision ranking does not lift these holds.

### P2 — Expanded manufacturing and advanced modeling

- Investigate assemblies, placement/alignment, sophisticated surfacing and fillets, real-world dimensional tolerance workflows and reliable STEP/STL/3MF interchange.
- Add capability based on evidence, engine readiness and owner-selected workflows. Preserve parametric editability and mobile ergonomics.

### P3 — AI-assisted modeling infrastructure (research/design first)

- Model request -> structured specification -> constraints/units/material assumptions -> typed CAD plan -> command execution -> incremental preview -> geometry validation -> user review/commit.
- Natural language directives should cause inspectable operations with live progress/cancel, not opaque whole-model replacement.
- Blueprint/photo path (future): image rectification, scale/reference calibration, feature/edge extraction, uncertainty tracking, dimension confirmation, plan generation and 3D reconstruction. A single 2D image often lacks depth/occluded information; request missing constraints rather than inventing manufacturing dimensions.
- Research local vs cloud inference, model size/latency, privacy, offline use, operating cost and App Store disclosures before service implementation. No unapproved external uploads of user drawings.
- Treat "real time" as a measurable experience goal (intermediate previews and responsiveness); record latency targets after experiments.

### P4 — Public Google Play readiness

- Signing key custody, versioned release provenance, reproducible release pipeline, disclosure of data/AI use, export permissions, licensing/dependency compliance, accessibility, privacy policy, crash recovery, automated Play policy check and staged Android phone compatibility testing.
- Release artifacts, signed AAB and deployment **only with explicit owner approval**. Debug APK is not a Play release.

## 5. Worker coordination and scope

**Source of truth:** [GLU Operations issue #3](https://github.com/AARMOU/GLUform/issues/3). Work is assigned via exact scoped issue IDs, worker identity, branch, file boundaries and acceptance checks. A worker may claim only one approved READY task at a time. Separate workers review rather than modify each other's active branches.

| Lane | Responsibility | Decision authority |
|---|---|---|
| Goose lead | Issue triage, user vision, priority, conflicts, routing, exact-head PR review, owner escalation | May define/schedule bounded work within approved scope; not merge/release |
| Engineering worker | Implement only approved specific issue in own branch; test, draft PR and CI | No self-assigned roadmap implementation; no merge or release |
| QA worker | Independent regression/code review, artifact provenance, performance evidence and device test planning | No silent modification of engineering branches; cannot claim physical device testing |
| Owner | Product WHAT, acceptance, priority overrides, merge/release spending approvals and lifts on explicit holds | Sole release/merge authority |

Worker autonomy is **code + PR + tests + test APK, no merge**. Prefer one active implementation per surface area to avoid races. Every CI result must refer to exact SHA; test APK must identify package, native bridge and debug signature. Distinguish prepared, built, independently reviewed, tested on device and approved for release.

## 6. Scheduling audit and capacity — November 11, 2026

- Account currently has **five active Automations** all dedicated to Arcana/Chimera, and no GLU Automations.
- Historical paused automations must remain paused and should not be reused to bypass a five-*active*-task limit.
- **No Arcana task or schedule may be paused, renamed, rescheduled or repurposed without owner approval.**
- Proposed eventual cadence *if capacity is explicitly authorized*: lead :05, Engineering :25, QA :45 hourly America/Denver, staggered to avoid racing. Platform Automations support at most hourly recurrence and do not promise continuous execution.
- Current state: **NOT RUNNING as unattended GPT schedules**. GitHub Actions CI may test on push; that is not an autonomous coding worker. For now issue #3 is manually coordinated, and work is explicitly dispatched when the owner requests.
- Before changing scheduling capacity, compare alternatives: retain all five Arcana automations and keep GLU manual; owner-approved reallocation of one or more slots; or a separate independently authorized scheduling infrastructure with real execution/monitoring and security controls.
- Any scheduling proposal must identify account limits, schedule overlap, cost/credit limits, failure retry, idempotency, stale leases, duplicate PR protection, evidence logging and escalation paths.

## 7. Unresolved choices for later, not blockers to present charter

AI inference location (on-device/cloud/hybrid), privacy defaults, performance-tier devices, support for tablets/desktop after Android phones, commercial terms if ever relevant, and acceptable model/AI running costs remain open decisions. Do not infer an answer from the Play Store goal.

## 8. Nonnegotiable change control

- **Never modify `main` or merge without explicit owner approval.**
- Never push public production builds, perform Play Store deployments or change signing/paid infrastructure without approval.
- **Do not implement held Canvas Import or Move Gizmo** solely because they are listed here.
- Do not modify `AARMOU/arcana_table`, `AARMOU/arcana-chimera`, or their schedules.
- Keep product specification separate from implementation authorization; all claims of completion require evidence.
