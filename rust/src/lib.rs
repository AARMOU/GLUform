//! Bridge between the Android app and the SolidCraft engine.
//!
//! Everything the app does to a design is an engine command (`execute`). The bridge adds what a
//! touch viewport needs on top: a compact binary scene (meshes, edges, sketch curves) and ray
//! picking against it.

use solidcraft_engine::doc::Document;
use solidcraft_engine::{Session, view};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};

pub mod jni_glue;

type Mesh = solidcraft_engine::geom::Mesh;

pub struct PickBody {
    pub name: String,
    pub mesh: Arc<Mesh>,
}

pub struct Bridge {
    pub session: Session,
    pub pick: Vec<PickBody>,
}

static BRIDGE: Mutex<Option<Bridge>> = Mutex::new(None);

pub fn with<R>(f: impl FnOnce(&mut Bridge) -> R) -> R {
    let mut g = BRIDGE.lock().unwrap_or_else(|e| e.into_inner());
    let b = g.get_or_insert_with(|| Bridge { session: Session::new(Document::default()), pick: Vec::new() });
    f(b)
}

/// Run a command; a panic inside the engine is reported as an error, not a crash of the app.
pub fn execute(cmd: &str, params: &str) -> String {
    let params: Value = match serde_json::from_str(if params.trim().is_empty() { "{}" } else { params }) {
        Ok(v) => v,
        Err(e) => return json!({"ok": false, "error": format!("bad parameters: {e}")}).to_string(),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| with(|b| b.session.execute(cmd, &params).map_err(|e| e.to_string()))));
    match r {
        Ok(Ok(v)) => json!({"ok": true, "result": v}).to_string(),
        Ok(Err(e)) => json!({"ok": false, "error": e}).to_string(),
        Err(_) => json!({"ok": false, "error": "the engine failed on this command (internal error); the design is unchanged"}).to_string(),
    }
}

/// Session state the UI shows on every refresh.
pub fn status() -> String {
    with(|b| {
        let s = &b.session;
        json!({
            "revision": s.revision,
            "active_sketch": s.active_sketch,
            "can_undo": !s.undo.is_empty(),
            "can_redo": !s.redo.is_empty(),
            "dirty": s.is_dirty(),
            "path": s.path,
        })
        .to_string()
    })
}

fn put_u32(o: &mut Vec<u8>, v: u32) {
    o.extend_from_slice(&v.to_le_bytes());
}
fn put_f32(o: &mut Vec<u8>, v: f64) {
    o.extend_from_slice(&(v as f32).to_le_bytes());
}
fn put_f64(o: &mut Vec<u8>, v: f64) {
    o.extend_from_slice(&v.to_le_bytes());
}
fn put_str(o: &mut Vec<u8>, s: &str) {
    put_u32(o, s.len() as u32);
    o.extend_from_slice(s.as_bytes());
    while o.len() % 4 != 0 {
        o.push(0);
    }
}

/// The scene as bytes (little endian); see `Scene.kt` for the reader.
pub fn scene() -> Vec<u8> {
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| with(scene_of)));
    r.unwrap_or_else(|_| {
        let mut o = Vec::new();
        put_u32(&mut o, 0x5343_4144);
        put_u32(&mut o, 0);
        put_u32(&mut o, 0);
        o
    })
}

fn scene_of(b: &mut Bridge) -> Vec<u8> {
    let mut o = Vec::with_capacity(1 << 16);
    put_u32(&mut o, 0x5343_4144);
    b.pick.clear();
    let s = &b.session;
    let Ok(st) = s.world_state() else {
        put_u32(&mut o, 0);
        put_u32(&mut o, 0);
        return o;
    };
    let bodies: Vec<_> = st.bodies.iter().filter(|x| s.body_visible(&x.name)).collect();
    put_u32(&mut o, bodies.len() as u32);
    for body in bodies {
        let mesh = body.mesh();
        put_str(&mut o, &body.name);
        let c = s.doc.body_color(body).unwrap_or([0.62, 0.66, 0.72]);
        for x in c {
            put_f32(&mut o, f64::from(x));
        }
        put_f32(&mut o, s.doc.model_body_opacity(body));
        put_u32(&mut o, mesh.positions.len() as u32);
        put_u32(&mut o, mesh.triangles.len() as u32);
        for p in &mesh.positions {
            put_f32(&mut o, p.x);
            put_f32(&mut o, p.y);
            put_f32(&mut o, p.z);
        }
        for i in 0..mesh.positions.len() {
            let (nx, ny, nz) = mesh.normals.get(i).map_or((0.0, 0.0, 1.0), |n| (n.x, n.y, n.z));
            put_f32(&mut o, nx);
            put_f32(&mut o, ny);
            put_f32(&mut o, nz);
        }
        for t in &mesh.triangles {
            for i in t {
                put_u32(&mut o, *i);
            }
        }
        for i in 0..mesh.triangles.len() {
            put_u32(&mut o, mesh.tri_face.get(i).copied().unwrap_or(0));
        }
        let shown: Vec<usize> = (0..mesh.edges.len()).filter(|i| mesh.shaded_edge_visible(*i)).collect();
        put_u32(&mut o, shown.len() as u32);
        for i in shown {
            put_u32(&mut o, i as u32);
            put_u32(&mut o, mesh.edges[i].len() as u32);
            for p in &mesh.edges[i] {
                put_f32(&mut o, p.x);
                put_f32(&mut o, p.y);
                put_f32(&mut o, p.z);
            }
        }
        b.pick.push(PickBody { name: body.name.clone(), mesh });
    }
    let sketches: Vec<_> = st.sketches.iter().filter(|ss| view::sketch_shown(s, ss.feature) || s.active_sketch == Some(ss.feature)).collect();
    put_u32(&mut o, sketches.len() as u32);
    for ss in sketches {
        let active = s.active_sketch == Some(ss.feature);
        put_u32(&mut o, ss.feature as u32);
        put_u32(&mut o, u32::from(active));
        for v in [ss.plane.origin, ss.plane.x, ss.plane.y] {
            put_f64(&mut o, v.x);
            put_f64(&mut o, v.y);
            put_f64(&mut o, v.z);
        }
        let lines = view::sketch_lines(&ss.sketch, &ss.plane, active, &ss.report.curve_determined);
        put_u32(&mut o, lines.len() as u32);
        for (pts, col, cons) in lines {
            o.extend_from_slice(&[col.0, col.1, col.2, u8::from(cons)]);
            put_u32(&mut o, pts.len() as u32);
            for p in pts {
                put_f32(&mut o, p.x);
                put_f32(&mut o, p.y);
                put_f32(&mut o, p.z);
            }
        }
    }
    o
}

fn sub(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}
fn add_s(a: [f64; 3], d: [f64; 3], t: f64) -> [f64; 3] {
    [a[0] + d[0] * t, a[1] + d[1] * t, a[2] + d[2] * t]
}

/// Cast a ray into the last scene. `tol_k`: the pick radius as world length per unit of
/// distance along the ray (plus `tol0` for orthographic views), for edges and vertices.
pub fn pick(o: [f64; 3], d: [f64; 3], tol0: f64, tol_k: f64) -> String {
    with(|b| {
        let dl = dot(d, d).sqrt();
        if dl < 1e-12 {
            return json!({}).to_string();
        }
        let d = [d[0] / dl, d[1] / dl, d[2] / dl];
        // nearest face
        let mut face: Option<(f64, usize, u32, [f64; 3])> = None;
        for (bi, pb) in b.pick.iter().enumerate() {
            let m = &pb.mesh;
            for (ti, t) in m.triangles.iter().enumerate() {
                let p = |i: u32| {
                    let v = m.positions[i as usize];
                    [v.x, v.y, v.z]
                };
                let (a, bb, c) = (p(t[0]), p(t[1]), p(t[2]));
                let (e1, e2) = (sub(bb, a), sub(c, a));
                let h = cross(d, e2);
                let det = dot(e1, h);
                if det.abs() < 1e-14 {
                    continue;
                }
                let f = 1.0 / det;
                let sv = sub(o, a);
                let u = f * dot(sv, h);
                if !(0.0..=1.0).contains(&u) {
                    continue;
                }
                let q = cross(sv, e1);
                let v = f * dot(d, q);
                if v < 0.0 || u + v > 1.0 {
                    continue;
                }
                let tt = f * dot(e2, q);
                if tt > 1e-9 && face.is_none_or(|x| tt < x.0) {
                    let n = cross(e1, e2);
                    face = Some((tt, bi, m.tri_face.get(ti).copied().unwrap_or(0), n));
                }
            }
        }
        // nearest edge and vertex to the ray, not hidden behind the face hit
        let limit = face.map_or(f64::INFINITY, |f| f.0);
        let mut edge: Option<(f64, usize, usize, [f64; 3], f64)> = None;
        let mut vertex: Option<(f64, usize, [f64; 3])> = None;
        for (bi, pb) in b.pick.iter().enumerate() {
            let m = &pb.mesh;
            if m.display_only_edges {
                continue;
            }
            for (ei, e) in m.edges.iter().enumerate() {
                if !m.shaded_edge_visible(ei) {
                    continue;
                }
                for (k, w) in e.windows(2).enumerate() {
                    let (a, c) = ([w[0].x, w[0].y, w[0].z], [w[1].x, w[1].y, w[1].z]);
                    let seg = sub(c, a);
                    let r = sub(a, o);
                    let (sd, ss, rd, rs) = (dot(seg, d), dot(seg, seg), dot(r, d), dot(r, seg));
                    let den = ss - sd * sd;
                    let mut u = if den.abs() > 1e-14 { (sd * rd - rs) / den } else { 0.0 };
                    u = u.clamp(0.0, 1.0);
                    let pt = add_s(a, seg, u);
                    let t = dot(sub(pt, o), d);
                    if t < 1e-9 {
                        continue;
                    }
                    let dist = {
                        let x = sub(pt, add_s(o, d, t));
                        dot(x, x).sqrt()
                    };
                    let tol = tol0 + tol_k * t;
                    if dist <= tol && t <= limit + tol * 2.0 {
                        let score = dist / tol;
                        if edge.is_none_or(|x| score < x.0) {
                            edge = Some((score, bi, ei, pt, t));
                        }
                    }
                    for end in if k == 0 { vec![a, c] } else { vec![c] } {
                        let te = dot(sub(end, o), d);
                        if te < 1e-9 {
                            continue;
                        }
                        let x = sub(end, add_s(o, d, te));
                        let de = dot(x, x).sqrt();
                        let tol = tol0 + tol_k * te;
                        let is_end = (end == a && k == 0) || (end == c && k + 2 == e.len());
                        if is_end && de <= tol && te <= limit + tol * 2.0 && vertex.is_none_or(|v| de / tol < v.0) {
                            vertex = Some((de / tol, bi, end));
                        }
                    }
                }
            }
        }
        let mut out = serde_json::Map::new();
        if let Some((t, bi, f, n)) = face {
            let nl = dot(n, n).sqrt().max(1e-30);
            let mut n = [n[0] / nl, n[1] / nl, n[2] / nl];
            if dot(n, d) > 0.0 {
                n = [-n[0], -n[1], -n[2]];
            }
            out.insert("face".into(), json!({"body": b.pick[bi].name, "face": f, "point": add_s(o, d, t), "normal": n, "t": t}));
        }
        if let Some((_, bi, ei, pt, t)) = edge {
            let e = &b.pick[bi].mesh.edges[ei];
            let mid = e[e.len() / 2];
            let pts: Vec<[f64; 3]> = e.iter().map(|p| [p.x, p.y, p.z]).collect();
            out.insert("edge".into(), json!({"body": b.pick[bi].name, "edge": ei, "point": pt, "mid": [mid.x, mid.y, mid.z], "t": t, "polyline": pts}));
        }
        if let Some((_, bi, p)) = vertex {
            out.insert("vertex".into(), json!({"body": b.pick[bi].name, "point": p}));
        }
        Value::Object(out).to_string()
    })
}

/// Start over with an empty design.
pub fn reset() {
    let mut g = BRIDGE.lock().unwrap_or_else(|e| e.into_inner());
    *g = Some(Bridge { session: Session::new(Document::default()), pick: Vec::new() });
}

/// The open sketch's curves as polylines in sketch coordinates, one entry per curve in the
/// order `sketch.inspect` lists them (for hit-testing touches against curves of any kind).
pub fn sketch_curves() -> String {
    with(|b| {
        let s = &b.session;
        let Some(id) = s.active_sketch else { return "[]".to_string() };
        let Ok(st) = s.world_state() else { return "[]".to_string() };
        let Some(ss) = st.sketches.iter().find(|x| x.feature == id) else { return "[]".to_string() };
        let pl = &ss.plane;
        let mut out: Vec<Vec<Vec<[f64; 2]>>> = Vec::new();
        for i in 0..ss.sketch.curves.len() {
            let parts = view::world_curve_parts(&ss.sketch, i, pl);
            out.push(
                parts
                    .iter()
                    .map(|part| {
                        part.iter()
                            .map(|p| {
                                let d = [p.x - pl.origin.x, p.y - pl.origin.y, p.z - pl.origin.z];
                                [dot(d, [pl.x.x, pl.x.y, pl.x.z]), dot(d, [pl.y.x, pl.y.y, pl.y.z])]
                            })
                            .collect()
                    })
                    .collect(),
            );
        }
        serde_json::to_string(&out).unwrap_or_else(|_| "[]".to_string())
    })
}
