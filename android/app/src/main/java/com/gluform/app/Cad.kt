package com.gluform.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gluform.app.engine.Native
import com.gluform.app.engine.Scene
import com.gluform.app.gl.Camera
import com.gluform.app.gl.FaceRef
import com.gluform.app.gl.Overlay
import com.gluform.app.gl.RenderState
import com.gluform.app.gl.ViewportHost
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class Pick(val kind: String, val body: String, val index: Int, val point: DoubleArray, val normal: DoubleArray? = null, val poly: FloatArray? = null) {
    fun json() = JSONArray().put(point[0]).put(point[1]).put(point[2])
    val label get() = "${kind.replaceFirstChar { it.uppercase() }} · $body"
}
class SkPoint(val id: String, val u: Double, val v: Double, val fixed: Boolean, val full: Boolean)
class SkCurve(val id: String, val type: String, val construction: Boolean, val full: Boolean, val parts: List<DoubleArray>)
class SkDim(val id: String, val param: String, val expr: String, val u: Double, val v: Double, val type: String)
class SkCon(val id: String, val name: String, val on: String)
class SketchInfo(
    val id: Long, val name: String, val dof: Int, val status: String, val profiles: Int,
    val points: List<SkPoint>, val curves: List<SkCurve>, val dims: List<SkDim>, val cons: List<SkCon>,
)

/** A point placed by a sketch tool: sketch coordinates, and the existing point it snapped to. */
class Placed(val u: Double, val v: Double, val ref: String? = null) {
    fun json(): Any = ref ?: JSONArray().put(u).put(v)
    fun xy(): JSONArray = JSONArray().put(u).put(v)
}

/** App state and the single door to the engine. UI state lives on the main thread; the engine on its own. */
object Cad : ViewportHost {
    private val engine = Executors.newSingleThreadExecutor { r -> Thread(null, r, "engine", 64L shl 20) }
    private val main = Handler(Looper.getMainLooper())

    var scene by mutableStateOf(Scene.EMPTY)
    private var cam by mutableStateOf(Camera())
    override var camera: Camera
        get() = cam
        set(v) { cam = v; push() }
    var doc by mutableStateOf(JSONObject())
    var sketch by mutableStateOf<SketchInfo?>(null)
    var busy by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)
    var lastError: String? = null
    private var revision = 0L; private var savedRevision = 0L
    fun markSaved() { savedRevision = revision; dirty = false }
    var canUndo by mutableStateOf(false); var canRedo by mutableStateOf(false); var dirty by mutableStateOf(false)
    var commands by mutableStateOf<List<JSONObject>>(emptyList())

    // view options
    var shaded by mutableStateOf(true); var edges by mutableStateOf(true); var grid by mutableStateOf(true); var xray by mutableStateOf(false)
    var light by mutableStateOf(false); var snap by mutableStateOf(true)
    var density = 2f; var viewW = 1; var viewH = 1
    var onFrame: ((RenderState) -> Unit)? = null

    // model selection and the open feature form
    val selection = mutableStateListOf<Pick>()
    var form by mutableStateOf<FormState?>(null)
    var measure by mutableStateOf(false)

    // sketch tool state
    var sketchTool by mutableStateOf("select")
    val placed = mutableStateListOf<Placed>()
    var cursor by mutableStateOf<Placed?>(null)
    val skSel = mutableStateListOf<String>()
    var polygonSides by mutableStateOf(6)
    private var chainStart: Placed? = null
    private var dragEntity: String? = null
    private var dragFrom: DoubleArray? = null

    // ---------- engine ----------

    fun start() {
        engine.execute {
            val cmds = runCatching { JSONObject(Native.execute("engine.commands", "{}")).getJSONArray("result") }.getOrNull()
            val list = ArrayList<JSONObject>()
            if (cmds != null) for (i in 0 until cmds.length()) list.add(cmds.getJSONObject(i))
            val snap = snapshot()
            main.post { commands = list; apply(snap) }
        }
    }

    private class Snap(val scene: Scene, val doc: JSONObject, val status: JSONObject, val sketch: SketchInfo?)

    private fun snapshot(): Snap {
        val status = runCatching { JSONObject(Native.status()) }.getOrDefault(JSONObject())
        val d = runCatching { JSONObject(Native.execute("document.inspect", "{}")).optJSONObject("result") }.getOrNull() ?: JSONObject()
        val sc = runCatching { Scene.parse(Native.scene()) }.getOrDefault(Scene.EMPTY)
        val sk = if (status.isNull("active_sketch")) null else runCatching { sketchInfo() }.getOrNull()
        return Snap(sc, d, status, sk)
    }

    private fun sketchInfo(): SketchInfo? {
        val j = JSONObject(Native.execute("sketch.inspect", "{}")).optJSONObject("result") ?: return null
        val polys = JSONArray(Native.sketchCurves())
        val pts = ArrayList<SkPoint>()
        val pa = j.getJSONArray("points")
        for (i in 0 until pa.length()) {
            val p = pa.getJSONObject(i); val at = p.getJSONArray("at")
            pts.add(SkPoint(p.getString("id"), at.getDouble(0), at.getDouble(1), p.optBoolean("fixed"), p.optBoolean("fully_constrained")))
        }
        val curves = ArrayList<SkCurve>()
        val ca = j.getJSONArray("curves")
        for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            val parts = ArrayList<DoubleArray>()
            val pp = polys.optJSONArray(i)
            if (pp != null) for (k in 0 until pp.length()) {
                val one = pp.getJSONArray(k); val a = DoubleArray(one.length() * 2)
                for (m in 0 until one.length()) { a[m * 2] = one.getJSONArray(m).getDouble(0); a[m * 2 + 1] = one.getJSONArray(m).getDouble(1) }
                parts.add(a)
            }
            curves.add(SkCurve(c.getString("id"), c.optString("type"), c.optBoolean("construction"), c.optBoolean("fully_constrained"), parts))
        }
        fun mid(c: SkCurve): DoubleArray? { val p = c.parts.firstOrNull() ?: return null; if (p.size < 2) return null; val m = (p.size / 4) * 2; return doubleArrayOf(p[m], p[m + 1]) }
        val dims = ArrayList<SkDim>(); val cons = ArrayList<SkCon>()
        val ka = j.getJSONArray("constraints")
        for (i in 0 until ka.length()) {
            val k = ka.getJSONObject(i)
            val at = ArrayList<DoubleArray>(); val names = ArrayList<String>()
            for (key in listOf("l", "c", "a", "b")) if (k.has(key)) curves.getOrNull(k.optInt(key, -1))?.let { c -> names.add(c.id); mid(c)?.let { at.add(it) } }
            for (key in listOf("p", "q")) if (k.has(key)) pts.getOrNull(k.optInt(key, -1))?.let { p -> names.add(p.id); at.add(doubleArrayOf(p.u, p.v)) }
            if (k.has("param")) {
                val u = if (at.isEmpty()) 0.0 else at.sumOf { it[0] } / at.size; val v = if (at.isEmpty()) 0.0 else at.sumOf { it[1] } / at.size
                dims.add(SkDim(k.getString("id"), k.getString("param"), k.optString("expression"), u, v, k.optString("type")))
            } else cons.add(SkCon(k.getString("id"), k.optString("name", k.optString("type")), names.joinToString(", ")))
        }
        return SketchInfo(j.optLong("id"), j.optString("name"), j.optInt("dof"), j.optString("status"), j.optJSONArray("profiles")?.length() ?: 0, pts, curves, dims, cons)
    }

    private fun apply(s: Snap) {
        val wasSketch = sketch?.id
        scene = s.scene; doc = s.doc; sketch = s.sketch
        canUndo = s.status.optBoolean("can_undo"); canRedo = s.status.optBoolean("can_redo"); revision = s.status.optLong("revision"); dirty = revision != savedRevision
        if (s.sketch == null) { skSel.clear(); placed.clear(); cursor = null } else skSel.removeAll { id -> s.sketch.points.none { it.id == id } && s.sketch.curves.none { it.id == id } && s.sketch.dims.none { it.id == id } }
        if (s.sketch != null && wasSketch != s.sketch.id) { sketchTool = "select"; lookAtSketch() }
        selection.removeAll { p -> s.scene.bodies.none { it.name == p.body } }
        push()
    }

    /** Run a command, then refresh everything the UI shows. `then` gets the result, or null when it failed. */
    fun exec(cmd: String, params: JSONObject = JSONObject(), quiet: Boolean = false, then: (JSONObject?) -> Unit = {}) {
        busy++
        engine.execute {
            val r = runCatching { JSONObject(Native.execute(cmd, params.toString())) }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "failed") }
            val snap = snapshot()
            main.post {
                busy--
                apply(snap)
                if (r.optBoolean("ok")) {
                    val res = r.opt("result")
                    val w = (res as? JSONObject)?.optString("warning", "")
                    if (!w.isNullOrEmpty() && w != "null" && !quiet) message = w
                    then(res as? JSONObject ?: JSONObject().put("value", res))
                } else { lastError = tidy(r.optString("error", "That did not work")); if (!quiet) message = lastError; then(null) }
            }
        }
    }

    /** Run several commands as one step: stops at the first failure and undoes what ran before it. */
    fun execAll(steps: List<Pair<String, JSONObject>>, then: (Boolean) -> Unit = {}) {
        busy++
        engine.execute {
            var err: String? = null; var done = 0
            for ((c, p) in steps) {
                val r = runCatching { JSONObject(Native.execute(c, p.toString())) }.getOrNull()
                if (r == null || !r.optBoolean("ok")) { err = r?.optString("error") ?: "failed"; break }
                done++
            }
            if (err != null) repeat(done) { Native.execute("edit.undo", "{}") }
            val snap = snapshot()
            main.post { busy--; apply(snap); if (err != null) message = tidy(err); then(err == null) }
        }
    }

    /** Ask the engine something without changing the UI state. */
    fun query(cmd: String, params: JSONObject = JSONObject(), then: (JSONObject?) -> Unit) {
        engine.execute {
            val r = runCatching { JSONObject(Native.execute(cmd, params.toString())) }.getOrNull()
            main.post { if (r != null && r.optBoolean("ok")) then(r.optJSONObject("result") ?: JSONObject().put("value", r.opt("result"))) else { message = tidy(r?.optString("error") ?: "failed"); then(null) } }
        }
    }

    fun onEngine(work: () -> String?, then: (String?) -> Unit) {
        busy++
        engine.execute {
            val err = runCatching { work() }.getOrElse { it.message ?: "failed" }
            val snap = snapshot()
            main.post { busy--; apply(snap); then(err) }
        }
    }

    private fun tidy(e: String) = e.removePrefix("invalid parameters for ").replace(Regex("^`[a-z_.0-9]+`: "), "").replaceFirstChar { it.uppercase() }

    fun undo() = exec("edit.undo")
    fun redo() = exec("edit.redo")
    fun newDesign() { engine.execute { Native.reset() }; selection.clear(); form = null; exec("document.inspect", quiet = true) { camera = Camera(); markSaved() } }

    // ---------- rendering ----------

    fun push() {
        val ov = ArrayList<Overlay>()
        val faces = ArrayList<FaceRef>()
        fun add(p: Pick, color: Int) {
            when (p.kind) {
                "face" -> faces.add(FaceRef(p.body, p.index))
                "edge" -> p.poly?.let { ov.add(Overlay(it, color, 3.5f)) }
                else -> {}
            }
        }
        selection.forEach { add(it, 0xFFFFA028.toInt()) }
        form?.let { f -> f.picks.values.forEach { l -> l.forEach { add(it, 0xFF4FC3F7.toInt()) } } }
        val sk = scene.active; val info = sketch
        if (sk != null && info != null) {
            // selected sketch curves
            for (c in info.curves) if (c.id in skSel) for (part in c.parts) ov.add(Overlay(world(sk, part), 0xFFFFA028.toInt(), 3.2f))
            preview(sk)?.let { ov.addAll(it) }
        }
        onFrame?.invoke(RenderState(scene, cam, faces, ov, shaded, edges, grid, xray, density, light))
    }

    private fun world(sk: com.gluform.app.engine.SketchView, uv: DoubleArray): FloatArray {
        val out = FloatArray(uv.size / 2 * 3)
        for (i in 0 until uv.size / 2) { val w = sk.toWorld(uv[i * 2], uv[i * 2 + 1]); out[i * 3] = w[0].toFloat(); out[i * 3 + 1] = w[1].toFloat(); out[i * 3 + 2] = w[2].toFloat() }
        return out
    }

    private fun circle(cu: Double, cv: Double, r: Double, a0: Double = 0.0, a1: Double = 2 * PI): DoubleArray {
        val n = 64; val o = DoubleArray((n + 1) * 2)
        for (i in 0..n) { val a = a0 + (a1 - a0) * i / n; o[i * 2] = cu + r * cos(a); o[i * 2 + 1] = cv + r * sin(a) }
        return o
    }

    /** Rubber-band preview of the shape being drawn. */
    private fun preview(sk: com.gluform.app.engine.SketchView): List<Overlay>? {
        val pts = ArrayList<Placed>(placed); cursor?.let { pts.add(it) }
        if (pts.isEmpty()) return null
        val col = 0xFF4FC3F7.toInt(); val out = ArrayList<Overlay>()
        fun line(vararg uv: Double) { out.add(Overlay(world(sk, uv), col, 2.2f)) }
        val a = pts[0]; val b = pts.getOrNull(1); val c = pts.getOrNull(2)
        when (sketchTool) {
            "line" -> if (b != null) line(a.u, a.v, b.u, b.v)
            "rect" -> if (b != null) line(a.u, a.v, b.u, a.v, b.u, b.v, a.u, b.v, a.u, a.v)
            "rectc" -> if (b != null) { val du = b.u - a.u; val dv = b.v - a.v; line(a.u - du, a.v - dv, a.u + du, a.v - dv, a.u + du, a.v + dv, a.u - du, a.v + dv, a.u - du, a.v - dv) }
            "circle" -> if (b != null) out.add(Overlay(world(sk, circle(a.u, a.v, hypot(b.u - a.u, b.v - a.v))), col, 2.2f))
            "circle2" -> if (b != null) out.add(Overlay(world(sk, circle((a.u + b.u) / 2, (a.v + b.v) / 2, hypot(b.u - a.u, b.v - a.v) / 2)), col, 2.2f))
            "polygon" -> if (b != null) {
                val r = hypot(b.u - a.u, b.v - a.v); val a0 = atan2(b.v - a.v, b.u - a.u); val n = polygonSides
                val o = DoubleArray((n + 1) * 2); for (i in 0..n) { o[i * 2] = a.u + r * cos(a0 + 2 * PI * i / n); o[i * 2 + 1] = a.v + r * sin(a0 + 2 * PI * i / n) }
                out.add(Overlay(world(sk, o), col, 2.2f))
            }
            "arc3" -> if (b != null) { if (c == null) line(a.u, a.v, b.u, b.v) else arc3(a, b, c)?.let { out.add(Overlay(world(sk, it), col, 2.2f)) } }
            "arcc" -> if (b != null) {
                val r = hypot(b.u - a.u, b.v - a.v)
                if (c == null) line(a.u, a.v, b.u, b.v) else { val s = atan2(b.v - a.v, b.u - a.u); var e = atan2(c.v - a.v, c.u - a.u); if (e <= s) e += 2 * PI; out.add(Overlay(world(sk, circle(a.u, a.v, r, s, e)), col, 2.2f)) }
            }
            "slot" -> if (b != null) {
                line(a.u, a.v, b.u, b.v)
                if (c != null) { val w = slotWidth(a, b, c) / 2; val d = atan2(b.v - a.v, b.u - a.u); val nx = -sin(d) * w; val ny = cos(d) * w
                    line(a.u + nx, a.v + ny, b.u + nx, b.v + ny); line(a.u - nx, a.v - ny, b.u - nx, b.v - ny)
                    out.add(Overlay(world(sk, circle(b.u, b.v, w, d - PI / 2, d + PI / 2)), col, 2.2f)); out.add(Overlay(world(sk, circle(a.u, a.v, w, d + PI / 2, d + 3 * PI / 2)), col, 2.2f)) }
            }
            "ellipse" -> if (b != null) {
                val ra = hypot(b.u - a.u, b.v - a.v); val d = atan2(b.v - a.v, b.u - a.u)
                if (c == null) line(a.u, a.v, b.u, b.v) else { val rb = abs(-(c.u - a.u) * sin(d) + (c.v - a.v) * cos(d)); val n = 64; val o = DoubleArray((n + 1) * 2)
                    for (i in 0..n) { val t = 2 * PI * i / n; o[i * 2] = a.u + ra * cos(t) * cos(d) - rb * sin(t) * sin(d); o[i * 2 + 1] = a.v + ra * cos(t) * sin(d) + rb * sin(t) * cos(d) }
                    out.add(Overlay(world(sk, o), col, 2.2f)) }
            }
            "spline" -> if (pts.size >= 2) { val o = DoubleArray(pts.size * 2); pts.forEachIndexed { i, p -> o[i * 2] = p.u; o[i * 2 + 1] = p.v }; out.add(Overlay(world(sk, o), col, 2.2f)) }
        }
        return out
    }

    private fun slotWidth(a: Placed, b: Placed, c: Placed): Double {
        val l = hypot(b.u - a.u, b.v - a.v).coerceAtLeast(1e-9)
        return max(0.1, 2 * abs((b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u)) / l)
    }

    private fun arc3(a: Placed, b: Placed, c: Placed): DoubleArray? {
        val d = 2 * (a.u * (b.v - c.v) + b.u * (c.v - a.v) + c.u * (a.v - b.v))
        if (abs(d) < 1e-9) return null
        val ux = ((a.u * a.u + a.v * a.v) * (b.v - c.v) + (b.u * b.u + b.v * b.v) * (c.v - a.v) + (c.u * c.u + c.v * c.v) * (a.v - b.v)) / d
        val uy = ((a.u * a.u + a.v * a.v) * (c.u - b.u) + (b.u * b.u + b.v * b.v) * (a.u - c.u) + (c.u * c.u + c.v * c.v) * (b.u - a.u)) / d
        val r = hypot(a.u - ux, a.v - uy)
        val s = atan2(a.v - uy, a.u - ux); var e = atan2(b.v - uy, b.u - ux); var m = atan2(c.v - uy, c.u - ux)
        while (e < s) e += 2 * PI; while (m < s) m += 2 * PI
        return if (m <= e) circle(ux, uy, r, s, e) else circle(ux, uy, r, e - 2 * PI, s)
    }

    fun fit() { camera = cam.fit(scene.bounds()) }
    fun view(yaw: Double, pitch: Double) { camera = cam.copy(yaw = yaw, pitch = pitch) }
    fun lookAtSketch() { val s = scene.active ?: return; camera = cam.lookAt(s.normal, s.x).let { c -> if (scene.bounds() == null) c.copy(tx = s.origin[0], ty = s.origin[1], tz = s.origin[2]) else c } }

    // ---------- touch ----------

    private fun sketchPoint(x: Float, y: Float): DoubleArray? {
        val sk = scene.active ?: return null
        val r = cam.ray(x, y, viewW, viewH); val n = sk.normal
        val den = r[3] * n[0] + r[4] * n[1] + r[5] * n[2]
        if (abs(den) < 1e-9) return null
        val t = ((sk.origin[0] - r[0]) * n[0] + (sk.origin[1] - r[1]) * n[1] + (sk.origin[2] - r[2]) * n[2]) / den
        return sk.toSketch(doubleArrayOf(r[0] + r[3] * t, r[1] + r[4] * t, r[2] + r[5] * t))
    }

    private fun px(): Double = cam.worldPerPixel(viewH)

    /** Where a touch lands in the sketch: an existing point when close to one, else the grid. */
    private fun place(x: Float, y: Float): Placed? {
        val uv = sketchPoint(x, y) ?: return null
        val tol = 16 * density * px()
        val near = sketch?.points?.minByOrNull { hypot(it.u - uv[0], it.v - uv[1]) }
        if (near != null && hypot(near.u - uv[0], near.v - uv[1]) < tol) return Placed(near.u, near.v, near.id)
        for (p in placed) if (hypot(p.u - uv[0], p.v - uv[1]) < tol) return Placed(p.u, p.v, p.ref)
        if (!snap) return Placed(uv[0], uv[1])
        val step = 10.0.pow(floor(log10(max(1e-6, cam.halfHeight() / 2.5)))) / 10
        return Placed(round(uv[0] / step) * step, round(uv[1] / step) * step)
    }

    /** The sketch entity under a touch: points first, then dimensions' curves. */
    fun hitSketch(x: Float, y: Float): String? {
        val uv = sketchPoint(x, y) ?: return null; val info = sketch ?: return null
        val tol = 18 * density * px()
        val p = info.points.minByOrNull { hypot(it.u - uv[0], it.v - uv[1]) }
        if (p != null && hypot(p.u - uv[0], p.v - uv[1]) < tol) return p.id
        var best: String? = null; var bd = tol
        for (c in info.curves) for (part in c.parts) {
            var i = 0
            while (i + 3 < part.size) {
                val d = segDist(uv[0], uv[1], part[i], part[i + 1], part[i + 2], part[i + 3])
                if (d < bd) { bd = d; best = c.id }
                i += 2
            }
        }
        return best
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
        val t = if (l < 1e-18) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l).coerceIn(0.0, 1.0)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    val drawTools = setOf("line", "rect", "rectc", "circle", "circle2", "arc3", "arcc", "polygon", "slot", "ellipse", "spline", "point")
    private fun needs(tool: String) = when (tool) { "point" -> 1; "arc3", "arcc", "slot", "ellipse" -> 3; "spline" -> Int.MAX_VALUE; else -> 2 }

    fun pickSketchTool(t: String) { finishChain(); sketchTool = t; placed.clear(); cursor = null; push() }

    /** End a line chain or commit a spline. */
    fun finishChain() {
        if (sketchTool == "spline" && placed.size >= 2) {
            val arr = JSONArray(); placed.forEach { arr.put(it.json()) }
            exec("sketch.spline.fit_point", JSONObject().put("points", arr))
        }
        placed.clear(); cursor = null; chainStart = null; push()
    }

    private fun addPlaced(p: Placed) {
        placed.add(p); cursor = null
        val t = sketchTool
        if (placed.size < needs(t)) { push(); return }
        val a = placed[0]; val b = placed.getOrNull(1); val c = placed.getOrNull(2)
        val j = JSONObject()
        when (t) {
            "point" -> { placed.clear(); exec("sketch.point", j.put("point", a.xy())) }
            "line" -> {
                if (hypot(b!!.u - a.u, b.v - a.v) < 1e-9) { placed.removeAt(1); return }
                if (chainStart == null) chainStart = a
                val closing = b.ref != null && b.ref == chainStart?.ref
                exec("sketch.line", j.put("points", JSONArray().put(a.json()).put(b.json()))) { r ->
                    placed.clear()
                    val id = r?.optJSONArray("curves")?.let { if (it.length() > 0) it.getString(it.length() - 1) else null }
                    if (r != null && id != null && !closing && sketchTool == "line") {
                        val end = sketch?.points?.firstOrNull { it.id == "$id.end" }
                        if (chainStart?.ref == null) chainStart = Placed(a.u, a.v, "$id.start")
                        placed.add(Placed(end?.u ?: b.u, end?.v ?: b.v, "$id.end"))
                    } else chainStart = null
                    push()
                }
            }
            "rect" -> { placed.clear(); exec("sketch.rectangle.two_point", j.put("p0", a.xy()).put("p1", b!!.xy())) }
            "rectc" -> { placed.clear(); exec("sketch.rectangle.center", j.put("center", a.json()).put("corner", b!!.xy())) }
            "circle" -> { placed.clear(); exec("sketch.circle.center", j.put("center", a.json()).put("radius", max(1e-3, hypot(b!!.u - a.u, b.v - a.v)))) }
            "circle2" -> { placed.clear(); exec("sketch.circle.two_point", j.put("p0", a.json()).put("p1", b!!.json())) }
            "polygon" -> { placed.clear(); exec("sketch.polygon.inscribed", j.put("center", a.json()).put("radius", max(1e-3, hypot(b!!.u - a.u, b.v - a.v))).put("sides", polygonSides).put("angle", Math.toDegrees(atan2(b.v - a.v, b.u - a.u)))) }
            "arc3" -> { placed.clear(); exec("sketch.arc.three_point", j.put("start", a.json()).put("end", b!!.json()).put("through", c!!.xy())) }
            "arcc" -> { placed.clear(); exec("sketch.arc.center_point", j.put("center", a.json()).put("start", b!!.json()).put("end", c!!.xy())) }
            "slot" -> { placed.clear(); exec("sketch.slot.center_to_center", j.put("p0", a.json()).put("p1", b!!.json()).put("width", slotWidth(a, b, c!!))) }
            "ellipse" -> { placed.clear(); exec("sketch.ellipse", j.put("center", a.json()).put("major", b!!.xy()).put("minor", c!!.xy())) }
        }
    }

    override fun panWithOneFinger() = sketch != null
    override fun toolOwnsDrag(x: Float, y: Float): Boolean {
        if (sketch == null) return false
        if (sketchTool in drawTools) return true
        if (sketchTool == "select") { dragEntity = hitSketch(x, y); return dragEntity != null }
        return false
    }

    override fun dragStart(x: Float, y: Float) {
        if (sketchTool in drawTools) {
            // a drag gives two points in one gesture when the shape has none yet
            if (placed.isEmpty() && sketchTool != "point" && sketchTool != "spline") place(x, y)?.let { placed.add(it) }
            push()
        } else dragFrom = sketchPoint(x, y)
    }

    private val liveTo = AtomicReference<DoubleArray?>(null)
    @Volatile private var liveFrom: DoubleArray? = null
    override fun dragMove(x: Float, y: Float) {
        if (sketchTool in drawTools) { cursor = place(x, y); push(); return }
        val e = dragEntity ?: return; val to = sketchPoint(x, y) ?: return
        if (liveFrom == null) liveFrom = dragFrom ?: to
        // only the newest position is sent: frames the engine has not caught up with are skipped
        if (liveTo.getAndSet(to) == null) engine.execute {
            val t = liveTo.getAndSet(null) ?: return@execute
            val f = liveFrom ?: t
            val q = JSONObject().put("entity", e).put("from", JSONArray().put(f[0]).put(f[1])).put("to", JSONArray().put(t[0]).put(t[1])).put("live", true)
            runCatching { Native.execute("sketch.drag", q.toString()) }
            liveFrom = t
            val sc = runCatching { Scene.parse(Native.scene()) }.getOrNull()
            val info = runCatching { sketchInfo() }.getOrNull()
            main.post { if (sc != null) scene = sc; if (info != null) sketch = info; push() }
        }
    }

    override fun dragEnd(x: Float, y: Float) {
        if (sketchTool in drawTools) { val p = place(x, y); cursor = null; if (p != null) addPlaced(p) else push(); return }
        if (dragEntity != null) { dragEntity = null; dragFrom = null; liveFrom = null; exec("sketch.drag_end", quiet = true) }
    }

    override fun dragCancel() {
        cursor = null
        if (sketchTool in drawTools) push()
        else if (dragEntity != null) { dragEntity = null; dragFrom = null; liveFrom = null; exec("sketch.drag_end", quiet = true) }
    }

    override fun tap(x: Float, y: Float) {
        val info = sketch
        if (info != null && sketchTool != "project") {
            when (sketchTool) {
                in drawTools -> place(x, y)?.let { addPlaced(it) }
                "trim", "extend" -> { val id = hitSketch(x, y); val uv = sketchPoint(x, y)
                    if (id != null && uv != null && info.curves.any { it.id == id }) exec("sketch.$sketchTool", JSONObject().put("curve", id).put("at", JSONArray().put(uv[0]).put(uv[1]))) }
                else -> { val id = hitSketch(x, y); if (id == null) skSel.clear() else if (id in skSel) skSel.remove(id) else skSel.add(id); push() }
            }
            return
        }
        val r = cam.ray(x, y, viewW, viewH)
        val tolK = if (cam.perspective) 14 * density * 2 * tan(cam.fov / 2) / viewH else 0.0
        val tol0 = if (cam.perspective) 0.0 else 14 * density * px()
        engine.execute {
            val j = runCatching { JSONObject(Native.pick(r[0], r[1], r[2], r[3], r[4], r[5], tol0, tolK)) }.getOrDefault(JSONObject())
            main.post { picked(j) }
        }
    }

    private fun arr3(a: JSONArray) = doubleArrayOf(a.getDouble(0), a.getDouble(1), a.getDouble(2))

    private fun picked(j: JSONObject) {
        val f = form; val want = f?.activeField()
        fun edge(): Pick? = j.optJSONObject("edge")?.let { e ->
            val pl = e.getJSONArray("polyline"); val fa = FloatArray(pl.length() * 3)
            for (i in 0 until pl.length()) { val p = pl.getJSONArray(i); fa[i * 3] = p.getDouble(0).toFloat(); fa[i * 3 + 1] = p.getDouble(1).toFloat(); fa[i * 3 + 2] = p.getDouble(2).toFloat() }
            Pick("edge", e.getString("body"), e.getInt("edge"), arr3(e.getJSONArray("point")), null, fa)
        }
        fun face(): Pick? = j.optJSONObject("face")?.let { Pick("face", it.getString("body"), it.getInt("face"), arr3(it.getJSONArray("point")), arr3(it.getJSONArray("normal"))) }
        fun vertex(): Pick? = j.optJSONObject("vertex")?.let { Pick("vertex", it.getString("body"), 0, arr3(it.getJSONArray("point"))) }
        if (sketch != null && sketchTool == "project") {
            val p = edge() ?: face() ?: return
            exec("sketch.project", JSONObject().put("refs", JSONArray().put(JSONObject().put(p.kind, p.json()).put("body", p.body))))
            return
        }
        if (f != null && want != null) {
            val p = when (want.kind) {
                Kind.EDGES -> edge()
                Kind.FACES, Kind.FACE, Kind.POINT -> face()
                Kind.PLANE -> face()
                Kind.AXIS -> edge() ?: face()
                Kind.BODY, Kind.BODIES -> face() ?: edge()
                else -> null
            } ?: return
            f.addPick(want, p); push(); return
        }
        val p = (if (measure) vertex() else null) ?: edge() ?: face()
        if (p == null) { selection.clear(); push(); return }
        val same = selection.indexOfFirst { it.kind == p.kind && it.body == p.body && it.index == p.index && (p.kind != "vertex" || it.point.contentEquals(p.point)) }
        if (same >= 0) selection.removeAt(same) else selection.add(p)
        push()
    }

    override fun longPress(x: Float, y: Float) { if (sketch != null) finishChain() else { selection.clear(); push() } }
    override fun doubleTap(x: Float, y: Float) { if (sketch != null && placed.isNotEmpty()) finishChain() else fit() }

    // ---------- sketch actions ----------

    fun skIsPoint(id: String) = sketch?.points?.any { it.id == id } == true
    fun skIsCurve(id: String) = sketch?.curves?.any { it.id == id } == true

    fun constrain(kind: String) {
        val s = skSel.toList()
        val j = JSONObject()
        fun ab() = if (s.size >= 2) j.put("a", s[0]).put("b", s[1]) else null
        val p: JSONObject? = when (kind) {
            "horizontal", "vertical" -> when {
                s.size == 1 && skIsCurve(s[0]) -> j.put("line", s[0]).put("mode", kind)
                s.size == 2 -> j.put("points", JSONArray().put(s[0]).put(s[1])).put("mode", kind)
                else -> null
            }
            "fix" -> if (s.isNotEmpty()) j.put("entities", JSONArray(s)) else null
            "symmetry" -> if (s.size == 3) j.put("a", s[0]).put("b", s[1]).put("line", s[2]) else null
            "midpoint" -> ab()
            else -> ab()
        }
        if (p == null) { message = when (kind) { "symmetry" -> "Select two entities, then the mirror line"; "fix" -> "Select what to fix"; "horizontal", "vertical" -> "Select a line or two points"; else -> "Select two entities first" }; return }
        val cmd = if (kind == "horizontal" || kind == "vertical") "sketch.constraint.horizontal_vertical" else "sketch.constraint.$kind"
        exec(cmd, p) { if (it != null) { skSel.clear(); push() } }
    }

    fun dimension(type: String, value: String) {
        val p = JSONObject().put("entities", JSONArray(skSel.toList()))
        if (type != "auto") p.put("type", type)
        if (value.isNotBlank()) p.put("value", value.trim())
        exec("sketch.dimension", p) { if (it != null) { skSel.clear(); push() } }
    }

    fun deleteSketchSelection() { if (skSel.isNotEmpty()) exec("sketch.delete", JSONObject().put("entities", JSONArray(skSel.toList()))) }
    fun toggleConstruction() { val c = skSel.filter { skIsCurve(it) }; if (c.isEmpty()) message = "Select curves first" else exec("sketch.construction", JSONObject().put("curves", JSONArray(c))) }

    fun projectPoint(p: DoubleArray): FloatArray? = cam.project(p, viewW, viewH)
}
