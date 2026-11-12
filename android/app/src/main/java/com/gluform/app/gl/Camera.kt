package com.gluform.app.gl

import android.opengl.Matrix
import kotlin.math.*

/** Orbit camera, Z up. Shared by the GL renderer and the 2D overlay (always copied, never mutated across threads). */
data class Camera(
    val tx: Double = 0.0, val ty: Double = 0.0, val tz: Double = 0.0,
    val yaw: Double = Math.toRadians(-35.0), val pitch: Double = Math.toRadians(28.0),
    val distance: Double = 200.0, val perspective: Boolean = false,
) {
    val fov get() = Math.toRadians(32.0)
    /** Unit vector from the target toward the eye. */
    fun back() = doubleArrayOf(-sin(yaw) * cos(pitch), -cos(yaw) * cos(pitch), sin(pitch))
    fun right() = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
    fun up(): DoubleArray { val b = back(); val r = right(); return doubleArrayOf(b[1] * r[2] - b[2] * r[1], b[2] * r[0] - b[0] * r[2], b[0] * r[1] - b[1] * r[0]) }
    fun eye(): DoubleArray { val b = back(); return doubleArrayOf(tx + b[0] * distance, ty + b[1] * distance, tz + b[2] * distance) }
    /** Half the height of the view at the target, in world units. */
    fun halfHeight() = distance * tan(fov / 2)
    fun worldPerPixel(h: Int) = 2 * halfHeight() / max(1, h)

    fun viewProj(w: Int, h: Int, radius: Double): FloatArray {
        val e = eye(); val u = up()
        val v = FloatArray(16); val p = FloatArray(16); val o = FloatArray(16)
        // relative to the target, so large coordinates keep float precision
        Matrix.setLookAtM(v, 0, (e[0] - tx).toFloat(), (e[1] - ty).toFloat(), (e[2] - tz).toFloat(), 0f, 0f, 0f, u[0].toFloat(), u[1].toFloat(), u[2].toFloat())
        val aspect = w.toFloat() / max(1, h)
        val r = max(radius, distance * 0.5)
        if (perspective) {
            val near = max(distance - r * 4, distance * 0.02).toFloat()
            Matrix.perspectiveM(p, 0, Math.toDegrees(fov).toFloat(), aspect, near, (distance + r * 4).toFloat())
        } else {
            val hh = halfHeight().toFloat()
            Matrix.orthoM(p, 0, -hh * aspect, hh * aspect, -hh, hh, (distance - r * 6).toFloat(), (distance + r * 6).toFloat())
        }
        Matrix.multiplyMM(o, 0, p, 0, v, 0)
        return o
    }

    /** World point to pixels (y down); null when behind the eye. */
    fun project(p: DoubleArray, w: Int, h: Int): FloatArray? {
        val d = doubleArrayOf(p[0] - tx, p[1] - ty, p[2] - tz)
        val r = right(); val u = up(); val b = back()
        val x = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
        val y = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        val z = distance - (d[0] * b[0] + d[1] * b[1] + d[2] * b[2])
        val hh = halfHeight()
        val k = if (perspective) { if (z <= distance * 0.01) return null; distance / z } else 1.0
        return floatArrayOf((w / 2.0 + x * k / hh * h / 2.0).toFloat(), (h / 2.0 - y * k / hh * h / 2.0).toFloat())
    }

    /** Ray through a pixel: origin (0..2) and unit direction (3..5). */
    fun ray(px: Float, py: Float, w: Int, h: Int): DoubleArray {
        val hh = halfHeight()
        val sx = (px - w / 2.0) / (h / 2.0) * hh
        val sy = (h / 2.0 - py) / (h / 2.0) * hh
        val r = right(); val u = up(); val b = back(); val e = eye()
        return if (perspective) {
            val d = doubleArrayOf(r[0] * sx + u[0] * sy - b[0] * distance, r[1] * sx + u[1] * sy - b[1] * distance, r[2] * sx + u[2] * sy - b[2] * distance)
            val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            doubleArrayOf(e[0], e[1], e[2], d[0] / l, d[1] / l, d[2] / l)
        } else {
            val far = distance * 20
            doubleArrayOf(e[0] + r[0] * sx + u[0] * sy + b[0] * far, e[1] + r[1] * sx + u[1] * sy + b[1] * far, e[2] + r[2] * sx + u[2] * sy + b[2] * far, -b[0], -b[1], -b[2])
        }
    }

    fun orbit(dxPx: Float, dyPx: Float): Camera {
        val lim = Math.toRadians(89.9)
        return copy(yaw = yaw + dxPx * 0.006, pitch = (pitch + dyPx * 0.006).coerceIn(-lim, lim))
    }
    fun pan(dxPx: Float, dyPx: Float, h: Int): Camera {
        val k = worldPerPixel(h); val r = right(); val u = up()
        return copy(tx = tx - r[0] * dxPx * k + u[0] * dyPx * k, ty = ty - r[1] * dxPx * k + u[1] * dyPx * k, tz = tz - r[2] * dxPx * k + u[2] * dyPx * k)
    }
    /** Zoom by `f` (>1 = closer), keeping the world point under the pixel fixed. */
    fun zoom(f: Float, px: Float, py: Float, w: Int, h: Int): Camera {
        val nd = (distance / f).coerceIn(1e-3, 1e7)
        val k = worldPerPixel(h) * (1 - nd / distance)
        val ox = px - w / 2f; val oy = py - h / 2f
        val r = right(); val u = up()
        return copy(distance = nd, tx = tx + (r[0] * ox - u[0] * oy) * k, ty = ty + (r[1] * ox - u[1] * oy) * k, tz = tz + (r[2] * ox - u[2] * oy) * k)
    }
    fun fit(b: FloatArray?): Camera {
        if (b == null) return copy(tx = 0.0, ty = 0.0, tz = 0.0, distance = 200.0)
        val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0; val cz = (b[2] + b[5]) / 2.0
        val rad = max(1.0, 0.5 * sqrt(((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])).toDouble()))
        return copy(tx = cx, ty = cy, tz = cz, distance = rad * 1.25 / tan(fov / 2))
    }
    /** Look straight at a plane (sketch mode): `n` toward the viewer, `x` to the right. */
    fun lookAt(n: DoubleArray, x: DoubleArray): Camera {
        val p = asin(n[2].coerceIn(-1.0, 1.0))
        val lim = Math.toRadians(89.9)
        val yw = if (abs(n[2]) > 0.999) atan2(-x[1], x[0]) else atan2(-n[0], -n[1])
        return copy(yaw = yw, pitch = p.coerceIn(-lim, lim))
    }
    companion object {
        val VIEWS = listOf(
            "Home" to (Math.toRadians(-35.0) to Math.toRadians(28.0)), "Front" to (0.0 to 0.0), "Back" to (PI to 0.0),
            "Right" to (-PI / 2 to 0.0), "Left" to (PI / 2 to 0.0), "Top" to (0.0 to Math.toRadians(89.9)), "Bottom" to (0.0 to Math.toRadians(-89.9)),
        )
    }
}
