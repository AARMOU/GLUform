# GLUform — GitHub Copilot Coding Agent Instructions

**Project identity:** GLUform is a public Android-first, manufacturing-precision CAD application for makers/hobbyists. It uses Kotlin, Jetpack Compose, Android OpenGL ES, an Android JNI bridge, and pinned SolidCraft Rust CAD engine source. GLU is not the Arcana/Argentum/Chimera project.

**Authority:** Read the specific issue assigned to you and [GLU operations](https://github.com/AARMOU/GLUform/issues/3) before starting. The [product charter](https://github.com/AARMOU/GLUform/pull/7) defines the direction, not broad permission to code. A feature being on the roadmap does not authorize implementation.

## Hard boundaries

- Work **only on the one explicitly assigned, owner-approved GLUform issue**. If the issue says research-only, provide findings rather than implementation.
- **Never merge any PR**, edit `main`, push to another contributor's branch, publish an APK/AAB/release, deploy, access signing keys, deploy cloud infrastructure, or increase paid-service spending. Open a **draft PR** for review.
- **OWNER HOLD:** Do not implement **Reference Image/Canvas Import** or **interactive Move/Copy/Rotate gizmo** until the owner independently lifts each hold. This prohibition includes partial or opportunistic implementations.
- Never edit, read private data from or dispatch work in `AARMOU/arcana_table` or `AARMOU/arcana-chimera`.
- Do not create unbounded subagents, paid cloud workloads, Copilot sessions, workflow rerun loops, or additional GitHub Actions workflows without specific approval.
- No AI/blueprint-to-3D implementation or external image/model upload is authorized solely by the long-term charter.
- Before writing code, check issue assignment, active claim, target branch and changed file list. **Do not duplicate a task already claimed by GLU Engineering.** When blocked, leave a concise evidence-based report.

## Engineering architecture

- Android project: `android/` with `com.gluform.app` package; Kotlin/Compose UI under `android/app/src/main/java/com/gluform/app/ui/`.
- Native CAD source: `rust/`, JNI glue in `rust/src/jni_glue.rs`, currently pinned to SolidCraft v0.40.0 and compiled into `libcadbridge.so` for `arm64-v8a`.
- Rendering and gestures: `android/app/src/main/java/com/gluform/app/gl/`; maintain explicit gesture ownership and OpenGL surface lifecycle.
- Preserve document geometry, units, parametric feature history, undo/redo and save/export compatibility. An apparently correct picture is not a geometry-correct implementation.
- Preserve 48dp touch targets when practicable, small viewport UI footprint, customizable gesture/precision modes and accessible command discovery.
- Prefer a small, isolated, reproducible patch over touching many features. Avoid unrelated refactors or changes to engine version.

## Validation and reporting

1. Reproduce a real failure or write the failing test before the fix. If a suspected bug cannot be reproduced, do not invent confirmation.
2. Run the most relevant local unit tests and Android build/CI where available. If the native Rust toolchain, SDK or real device is absent, report these limitations; never claim a test you couldn't run.
3. Ensure packaged ARM64 test APK contains `lib/arm64-v8a/libcadbridge.so` if building. Describe debug vs release signing accurately.
4. Physical Samsung Fold behavior is **UNVERIFIED** until an actual on-device result is supplied. CI green != device passed.
5. Draft PR body must include: issue link, exact source base/head, files changed, reproduction steps, tests with PASS/FAIL/NOT RUN and logs, relevant artifact URL if produced, regression risks, and remaining manual device acceptance.
6. Report states `CLAIMED` / `IN_PROGRESS` / `IN_REVIEW` / `BLOCKED` accurately to the assigned issue, without repeated status spam.

**Current first scoped GLU P0 engineering work:** [#8 Android autosave-on-stop](https://github.com/AARMOU/GLUform/issues/8), already assigned to the scheduled GLU Engineering worker. **Copilot must not claim #8 concurrently.** Future Copilot tasks need a separate READY issue, a unique code area and explicit launch/cost authorization.
