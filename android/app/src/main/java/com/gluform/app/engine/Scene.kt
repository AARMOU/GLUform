package com.gluform.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

class EdgeLine(val index: Int, val pts: FloatArray)
class BodyMesh(
    val name: String, val color: FloatArray, val opacity: Float,
    val positions: FloatArray, val normals: FloatArray, val indices: IntArray, val triFace: IntArray,
    val edges: List<EdgeLine>,
)
class SketchLine(val r: Int, val g: Int, val b: Int, val construction: Boolean, val pts: FloatArray)
class SketchView(val feature: Long, val active: Boolean, val origin: DoubleArray, val x: DoubleArray, val y: DoubleArray, val lines: List<SketchLine>) {
    val normal: DoubleArray get() = doubleArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
    fun toWorld(u: Double, v: Double) = doubleArrayOf(origin[0] + x[0] * u + y[0] * v, origin[1] + x[1] * u + y[1] * v, origin[2] + x[2] * u + y[2] * v)
    fun toSketch(p: DoubleArray): DoubleArray {
        val d = doubleArrayOf(p[0] - origin[0], p[1] - origin[1], p[2] - origin[2])
        return doubleArrayOf(d[0] * x[0] + d[1] * x[1] + d[2] * x[2], d[0] * y[0] + d[1] * y[1] + d[2] * y[2])
    }
}

class Scene(val bodies: List<BodyMesh>, val sketches: List<SketchView>) {
    val active: SketchView? get() = sketches.firstOrNull { it.active }

    /** min/max corners of everything shown, or null when the scene is empty. */
    fun bounds(): FloatArray? {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var any = false
        fun add(a: FloatArray) {
            var i = 0
            while (i + 2 < a.size) {
                for (k in 0..2) { if (a[i + k] < b[k]) b[k] = a[i + k]; if (a[i + k] > b[3 + k]) b[3 + k] = a[i + k] }
                any = true; i += 3
            }
        }
        bodies.forEach { add(it.positions) }
        sketches.forEach { s -> s.lines.forEach { add(it.pts) } }
        return if (any) b else null
    }

    companion object {
        val EMPTY = Scene(emptyList(), emptyList())

        fun parse(bytes: ByteArray): Scene {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.remaining() < 12 || b.int != 0x53434144) return EMPTY
            fun str(): String {
                val n = b.int; val a = ByteArray(n); b.get(a)
                while (b.position() % 4 != 0) b.get()
                return String(a, Charsets.UTF_8)
            }
            fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + n * 4) }
            fun ints(n: Int) = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + n * 4) }
            val bodies = ArrayList<BodyMesh>()
            repeat(b.int) {
                val name = str()
                val color = floats(3); val opacity = b.float
                val nv = b.int; val nt = b.int
                val pos = floats(nv * 3); val nor = floats(nv * 3); val idx = ints(nt * 3); val tf = ints(nt)
                val edges = ArrayList<EdgeLine>()
                repeat(b.int) { val i = b.int; val n = b.int; edges.add(EdgeLine(i, floats(n * 3))) }
                bodies.add(BodyMesh(name, color, opacity, pos, nor, idx, tf, edges))
            }
            val sketches = ArrayList<SketchView>()
            repeat(b.int) {
                val feature = b.int.toLong() and 0xffffffffL
                val active = b.int != 0
                fun v3() = doubleArrayOf(b.double, b.double, b.double)
                val o = v3(); val x = v3(); val y = v3()
                val lines = ArrayList<SketchLine>()
                repeat(b.int) {
                    val r = b.get().toInt() and 255; val g = b.get().toInt() and 255; val bl = b.get().toInt() and 255; val c = b.get().toInt() != 0
                    val n = b.int
                    lines.add(SketchLine(r, g, bl, c, floats(n * 3)))
                }
                sketches.add(SketchView(feature, active, o, x, y, lines))
            }
            return Scene(bodies, sketches)
        }
    }
}
