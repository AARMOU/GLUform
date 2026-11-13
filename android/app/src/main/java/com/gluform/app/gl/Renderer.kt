package com.gluform.app.gl

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import com.gluform.app.engine.Scene
import com.gluform.app.engine.SketchView
import com.gluform.app.Diagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** A world polyline to draw on top (previews, highlights). */
class Overlay(val pts: FloatArray, val color: Int, val widthDp: Float = 2.5f, val closed: Boolean = false)
class FaceRef(val body: String, val face: Int)

/** Everything a frame needs; replaced as a whole from the UI thread. */
class RenderState(
    val scene: Scene = Scene.EMPTY, val camera: Camera = Camera(),
    val faces: List<FaceRef> = emptyList(), val overlays: List<Overlay> = emptyList(),
    val shaded: Boolean = true, val edges: Boolean = true, val grid: Boolean = true, val xray: Boolean = false,
    val density: Float = 2f, val light: Boolean = false,
)

class Renderer : GLSurfaceView.Renderer {
    @Volatile var state = RenderState()
    @Volatile var width = 1; @Volatile var height = 1
    private var built: Scene? = null
    private var meshProg = 0; private var lineProg = 0
    private class GpuBody(val name: String, val vao: Int, val bufs: IntArray, val count: Int, val color: FloatArray, val opacity: Float, val triFace: IntArray, val indices: IntArray)
    private class LineBuf { var vao = 0; var vbo = 0; var ibo = 0; var count = 0 }
    private var bodies = listOf<GpuBody>()
    private val edgeBuf = LineBuf(); private val sketchBuf = LineBuf(); private val activeBuf = LineBuf(); private val dynBuf = LineBuf(); private val topBuf = LineBuf()
    private var selVao = 0; private var selIbo = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Diagnostics.event("gl.surface.created", "vendor=" + glGetString(GL_VENDOR) + " renderer=" + glGetString(GL_RENDERER) + " version=" + glGetString(GL_VERSION))
        meshProg = program(MESH_VS, MESH_FS); lineProg = program(LINE_VS, LINE_FS)
        built = null; bodies = emptyList(); selVao = 0; selIbo = 0
        for (b in listOf(edgeBuf, sketchBuf, activeBuf, dynBuf, topBuf)) { b.vao = 0; b.count = 0 }
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL)
    }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; glViewport(0, 0, w, h); Diagnostics.event("gl.surface.size", w.toString() + "x" + h) }

    override fun onDrawFrame(gl: GL10?) {
        val st = state; val cam = st.camera
        if (st.light) glClearColor(0.93f, 0.94f, 0.96f, 1f) else glClearColor(0.105f, 0.118f, 0.141f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        if (built !== st.scene) { upload(st.scene, st.light); built = st.scene }
        val b = st.scene.bounds()
        val radius = if (b == null) 100.0 else {
            val c = doubleArrayOf((b[0] + b[3]) / 2.0 - cam.tx, (b[1] + b[4]) / 2.0 - cam.ty, (b[2] + b[5]) / 2.0 - cam.tz)
            sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]) + 0.5 * sqrt(((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])).toDouble())
        }
        val mvp = cam.viewProj(width, height, radius)
        val tgt = floatArrayOf(cam.tx.toFloat(), cam.ty.toFloat(), cam.tz.toFloat())
        val back = cam.back()

        // grid and axes (under the model)
        val dyn = ArrayList<Float>(); val dynIdx = ArrayList<Int>()
        if (st.grid) grid(st, dyn, dynIdx)
        fill(dynBuf, dyn, dynIdx, true)
        // The line shader outputs RGBA; blending is required for subtle grid alpha.
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false); drawLines(dynBuf, mvp, tgt, 0f); glDepthMask(true)
        glDisable(GL_BLEND)

        if (st.shaded) {
            glUseProgram(meshProg)
            glUniformMatrix4fv(glGetUniformLocation(meshProg, "uMVP"), 1, false, mvp, 0)
            glUniform3fv(glGetUniformLocation(meshProg, "uTarget"), 1, tgt, 0)
            glUniform3f(glGetUniformLocation(meshProg, "uLight"), back[0].toFloat(), back[1].toFloat(), back[2].toFloat())
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1f, 1f)
            val cl = glGetUniformLocation(meshProg, "uColor")
            for (pass in 0..1) for (g in bodies) {
                val glass = st.xray || g.opacity < 0.999f
                if ((pass == 1) != glass) continue
                val a = if (st.xray) 0.35f else max(0.15f, g.opacity)
                if (glass) { glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false) }
                glUniform4f(cl, g.color[0], g.color[1], g.color[2], a)
                glBindVertexArray(g.vao); glDrawElements(GL_TRIANGLES, g.count, GL_UNSIGNED_INT, 0)
                if (glass) { glDisable(GL_BLEND); glDepthMask(true) }
            }
            // selected faces
            if (st.faces.isNotEmpty()) {
                glPolygonOffset(-1f, -1f)
                glUniform4f(cl, 1.0f, 0.62f, 0.16f, 1f)
                for (g in bodies) {
                    val want = st.faces.filter { it.body == g.name }.map { it.face }.toSet()
                    if (want.isEmpty()) continue
                    val sel = ArrayList<Int>()
                    for (t in g.triFace.indices) if (g.triFace[t] in want) { sel.add(g.indices[t * 3]); sel.add(g.indices[t * 3 + 1]); sel.add(g.indices[t * 3 + 2]) }
                    if (sel.isEmpty()) continue
                    if (selVao == 0) { val a = IntArray(1); glGenVertexArrays(1, a, 0); selVao = a[0]; glGenBuffers(1, a, 0); selIbo = a[0] }
                    glBindVertexArray(selVao)
                    glBindBuffer(GL_ARRAY_BUFFER, g.bufs[0]); glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
                    glBindBuffer(GL_ARRAY_BUFFER, g.bufs[1]); glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, selIbo)
                    val ib = ByteBuffer.allocateDirect(sel.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer(); for (i in sel) ib.put(i); ib.position(0)
                    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sel.size * 4, ib, GL_STREAM_DRAW)
                    glDrawElements(GL_TRIANGLES, sel.size, GL_UNSIGNED_INT, 0)
                }
            }
            glDisable(GL_POLYGON_OFFSET_FILL)
            glBindVertexArray(0)
        }
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        if (st.edges || !st.shaded) drawLines(edgeBuf, mvp, tgt, 0.0006f)
        drawLines(sketchBuf, mvp, tgt, 0.0006f)
        glDisable(GL_DEPTH_TEST)
        drawLines(activeBuf, mvp, tgt, 0f)
        val top = ArrayList<Float>(); val topIdx = ArrayList<Int>()
        for (o in st.overlays) {
            val c = floatArrayOf((o.color shr 16 and 255) / 255f, (o.color shr 8 and 255) / 255f, (o.color and 255) / 255f, (o.color ushr 24) / 255f)
            polyline(top, topIdx, o.pts, c, o.widthDp * st.density, o.closed)
        }
        fill(topBuf, top, topIdx, true)
        drawLines(topBuf, mvp, tgt, 0f)
        glEnable(GL_DEPTH_TEST); glDisable(GL_BLEND)
        val glError = glGetError()
        Diagnostics.event("gl.frame", "size=" + width + "x" + height + " bodies=" + st.scene.bodies.size + " sketches=" + st.scene.sketches.size + " sketchLineCounts=" + st.scene.sketches.map { it.lines.size } + " activeLines=" + (st.scene.active?.lines?.size ?: 0) + " gpuStaticIndices=" + sketchBuf.count + " gpuActiveIndices=" + activeBuf.count + " overlayIndices=" + topBuf.count + " error=" + glError + " cameraDistance=" + cam.distance)
    }

    private fun grid(st: RenderState, out: ArrayList<Float>, idx: ArrayList<Int>) {
        val cam = st.camera; val sk: SketchView? = st.scene.active
        val o = sk?.origin ?: doubleArrayOf(0.0, 0.0, 0.0); val x = sk?.x ?: doubleArrayOf(1.0, 0.0, 0.0); val y = sk?.y ?: doubleArrayOf(0.0, 1.0, 0.0)
        val hh = cam.halfHeight()
        val step = 10.0.pow(floor(log10(max(1e-6, hh / 2.5))))
        val d = doubleArrayOf(cam.tx - o[0], cam.ty - o[1], cam.tz - o[2])
        val cu = round((d[0] * x[0] + d[1] * x[1] + d[2] * x[2]) / step) * step
        val cv = round((d[0] * y[0] + d[1] * y[1] + d[2] * y[2]) / step) * step
        val n = 30; val ext = n * step
        val ink = if (st.light) 0.25f else 0.85f
        fun p(u: Double, v: Double) = floatArrayOf((o[0] + x[0] * u + y[0] * v).toFloat(), (o[1] + x[1] * u + y[1] * v).toFloat(), (o[2] + x[2] * u + y[2] * v).toFloat())
        for (i in -n..n) {
            val major = (round((cu + i * step) / step).toLong() % 10L) == 0L
            val c = floatArrayOf(ink, ink, ink, if (major) 0.18f else 0.06f)
            val a = p(cu + i * step, cv - ext); val b = p(cu + i * step, cv + ext)
            polyline(out, idx, a + b, c, st.density * 0.8f, false)
            val a2 = p(cu - ext, cv + i * step); val b2 = p(cu + ext, cv + i * step)
            val major2 = (round((cv + i * step) / step).toLong() % 10L) == 0L
            polyline(out, idx, a2 + b2, floatArrayOf(ink, ink, ink, if (major2) 0.18f else 0.06f), st.density * 0.8f, false)
        }
        val len = (hh * 0.35).toFloat()
        val oo = floatArrayOf(o[0].toFloat(), o[1].toFloat(), o[2].toFloat())
        if (sk == null) {
            polyline(out, idx, floatArrayOf(0f, 0f, 0f, len, 0f, 0f), floatArrayOf(0.90f, 0.30f, 0.30f, 1f), st.density * 1.6f, false)
            polyline(out, idx, floatArrayOf(0f, 0f, 0f, 0f, len, 0f), floatArrayOf(0.35f, 0.75f, 0.35f, 1f), st.density * 1.6f, false)
            polyline(out, idx, floatArrayOf(0f, 0f, 0f, 0f, 0f, len), floatArrayOf(0.35f, 0.55f, 0.95f, 1f), st.density * 1.6f, false)
        } else {
            polyline(out, idx, oo + p(len.toDouble(), 0.0), floatArrayOf(0.90f, 0.30f, 0.30f, 1f), st.density * 1.6f, false)
            polyline(out, idx, oo + p(0.0, len.toDouble()), floatArrayOf(0.35f, 0.75f, 0.35f, 1f), st.density * 1.6f, false)
        }
    }

    /** Append a polyline as screen-space quads: 11 floats a vertex (pos, other end, side*width, rgba). */
    private fun polyline(out: ArrayList<Float>, idx: ArrayList<Int>, p: FloatArray, c: FloatArray, widthPx: Float, closed: Boolean) {
        val n = p.size / 3
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val a = i * 3; val b = ((i + 1) % n) * 3
            val base = out.size / 11
            fun v(s: Int, e: Int, side: Float) {
                out.add(p[s]); out.add(p[s + 1]); out.add(p[s + 2]); out.add(p[e]); out.add(p[e + 1]); out.add(p[e + 2]); out.add(side * widthPx)
                out.add(c[0]); out.add(c[1]); out.add(c[2]); out.add(c[3])
            }
            v(a, b, 1f); v(a, b, -1f); v(b, a, -1f); v(b, a, 1f)
            idx.add(base); idx.add(base + 1); idx.add(base + 2); idx.add(base + 2); idx.add(base + 1); idx.add(base + 3)
        }
    }

    private fun fill(lb: LineBuf, v: ArrayList<Float>, idx: ArrayList<Int>, dynamic: Boolean) {
        if (lb.vao == 0) {
            val a = IntArray(3); glGenVertexArrays(1, a, 0); lb.vao = a[0]; glGenBuffers(2, a, 1); lb.vbo = a[1]; lb.ibo = a[2]
            glBindVertexArray(lb.vao); glBindBuffer(GL_ARRAY_BUFFER, lb.vbo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, lb.ibo)
            val s = 44
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, s, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, s, 12)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, s, 24)
            glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, false, s, 28)
        }
        lb.count = idx.size
        if (idx.isEmpty()) return
        val fb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); for (f in v) fb.put(f); fb.position(0)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer(); for (i in idx) ib.put(i); ib.position(0)
        val usage = if (dynamic) GL_STREAM_DRAW else GL_STATIC_DRAW
        glBindVertexArray(lb.vao)
        glBindBuffer(GL_ARRAY_BUFFER, lb.vbo); glBufferData(GL_ARRAY_BUFFER, v.size * 4, fb, usage)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, lb.ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, usage)
        glBindVertexArray(0)
    }

    private fun drawLines(lb: LineBuf, mvp: FloatArray, tgt: FloatArray, bias: Float) {
        if (lb.count == 0 || lb.vao == 0) return
        glUseProgram(lineProg)
        glUniformMatrix4fv(glGetUniformLocation(lineProg, "uMVP"), 1, false, mvp, 0)
        glUniform3fv(glGetUniformLocation(lineProg, "uTarget"), 1, tgt, 0)
        glUniform2f(glGetUniformLocation(lineProg, "uView"), width.toFloat(), height.toFloat())
        glUniform1f(glGetUniformLocation(lineProg, "uBias"), bias)
        glBindVertexArray(lb.vao); glDrawElements(GL_TRIANGLES, lb.count, GL_UNSIGNED_INT, 0); glBindVertexArray(0)
    }

    private fun upload(scene: Scene, light: Boolean) {
        for (g in bodies) { glDeleteBuffers(3, g.bufs, 0); glDeleteVertexArrays(1, intArrayOf(g.vao), 0) }
        val d = state.density
        val list = ArrayList<GpuBody>()
        val ev = ArrayList<Float>(); val ei = ArrayList<Int>()
        val edgeColor = if (light) floatArrayOf(0.10f, 0.11f, 0.13f, 1f) else floatArrayOf(0.04f, 0.05f, 0.06f, 1f)
        for (m in scene.bodies) {
            val a = IntArray(1); glGenVertexArrays(1, a, 0); val vao = a[0]
            val bufs = IntArray(3); glGenBuffers(3, bufs, 0)
            glBindVertexArray(vao)
            fun fbuf(x: FloatArray) = ByteBuffer.allocateDirect(x.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(x).also { it.position(0) }
            glBindBuffer(GL_ARRAY_BUFFER, bufs[0]); glBufferData(GL_ARRAY_BUFFER, m.positions.size * 4, fbuf(m.positions), GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glBindBuffer(GL_ARRAY_BUFFER, bufs[1]); glBufferData(GL_ARRAY_BUFFER, m.normals.size * 4, fbuf(m.normals), GL_STATIC_DRAW)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(m.indices); ib.position(0)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, bufs[2]); glBufferData(GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GL_STATIC_DRAW)
            list.add(GpuBody(m.name, vao, bufs, m.indices.size, m.color, m.opacity, m.triFace, m.indices))
            for (e in m.edges) polyline(ev, ei, e.pts, edgeColor, d * 1.1f, false)
        }
        glBindVertexArray(0)
        bodies = list
        fill(edgeBuf, ev, ei, false)
        val sv = ArrayList<Float>(); val si = ArrayList<Int>(); val av = ArrayList<Float>(); val ai = ArrayList<Int>()
        for (s in scene.sketches) for (l in s.lines) {
            val c = floatArrayOf(l.r / 255f, l.g / 255f, l.b / 255f, if (l.construction) 0.75f else 1f)
            if (s.active) polyline(av, ai, l.pts, c, d * (if (l.construction) 1.1f else 1.9f), false)
            else polyline(sv, si, l.pts, c, d * (if (l.construction) 0.9f else 1.4f), false)
        }
        fill(sketchBuf, sv, si, false); fill(activeBuf, av, ai, false)
        Diagnostics.event("gl.upload", "bodies=" + scene.bodies.size + " sketches=" + scene.sketches.size + " activeLines=" + scene.sketches.filter { it.active }.sumOf { it.lines.size } + " gpuStaticIndices=" + si.size + " gpuActiveIndices=" + ai.size)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(t: Int, src: String): Int { val s = glCreateShader(t); glShaderSource(s, src); glCompileShader(s); return s }
        val p = glCreateProgram(); glAttachShader(p, sh(GL_VERTEX_SHADER, vs)); glAttachShader(p, sh(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos"); glBindAttribLocation(p, 1, "aB"); glBindAttribLocation(p, 2, "aSide"); glBindAttribLocation(p, 3, "aColor")
        glLinkProgram(p); return p
    }

    companion object {
        const val MESH_VS = """#version 300 es
uniform mat4 uMVP; uniform vec3 uTarget;
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aB;
out vec3 vN;
void main(){ vN = aB; gl_Position = uMVP * vec4(aPos - uTarget, 1.0); }"""
        const val MESH_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor; uniform vec3 uLight;
in vec3 vN; out vec4 o;
void main(){
  vec3 n = normalize(vN);
  float d = abs(dot(n, normalize(uLight + vec3(0.25, -0.15, 0.35))));
  float s = pow(d, 24.0) * 0.12;
  o = vec4(uColor.rgb * (0.38 + 0.62 * d) + s, uColor.a);
}"""
        const val LINE_VS = """#version 300 es
uniform mat4 uMVP; uniform vec3 uTarget; uniform vec2 uView; uniform float uBias;
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aB; layout(location=2) in float aSide; layout(location=3) in vec4 aColor;
out vec4 vC;
void main(){
  vec4 a = uMVP * vec4(aPos - uTarget, 1.0);
  vec4 b = uMVP * vec4(aB - uTarget, 1.0);
  vec2 sa = a.xy / max(a.w, 1e-6) * uView; vec2 sb = b.xy / max(b.w, 1e-6) * uView;
  vec2 dir = sb - sa; float l = length(dir);
  dir = l > 1e-6 ? dir / l : vec2(1.0, 0.0);
  vec2 nrm = vec2(-dir.y, dir.x) * aSide;
  a.xy += nrm / uView * a.w;
  a.z -= uBias * a.w;
  vC = aColor; gl_Position = a;
}"""
        const val LINE_FS = """#version 300 es
precision mediump float;
in vec4 vC; out vec4 o;
void main(){ o = vC; }"""
    }
}
