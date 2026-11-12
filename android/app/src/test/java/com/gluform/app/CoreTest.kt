package com.gluform.app

import com.gluform.app.engine.Scene
import com.gluform.app.gl.Camera
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class CoreTest {
    @Test fun parsesSceneFromTheBridge() {
        val s = Scene.parse(File(System.getenv("GLUFORM_SCENE") ?: "scene.bin").readBytes())
        assertTrue(s.bodies.isNotEmpty())
        val b = s.bodies[0]
        println("body ${b.name}: ${b.positions.size / 3} verts, ${b.indices.size / 3} tris, ${b.edges.size} edges; sketches ${s.sketches.size}")
        assertTrue(b.name.startsWith("Body"))
        assertTrue(b.indices.size > 30 && b.indices.all { it >= 0 && it < b.positions.size / 3 })
        assertEquals(b.indices.size / 3, b.triFace.size)
        assertEquals(b.positions.size, b.normals.size)
        assertTrue(b.edges.size > 10)
        val bb = s.bounds()!!
        println("bounds ${bb.toList()}")
        assertTrue(bb[3] - bb[0] > 50 && bb[5] - bb[2] > 5)
        val sk = s.active!!
        assertTrue(sk.lines.isNotEmpty())
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0), sk.normal, 1e-9)
    }

    @Test fun cameraRayAndProjectAgree() {
        for (persp in listOf(false, true)) {
            val c = Camera(tx = 10.0, ty = -5.0, tz = 3.0, perspective = persp)
            val p = doubleArrayOf(22.0, 4.0, 9.0)
            val s = c.project(p, 1600, 1000)!!
            val r = c.ray(s[0], s[1], 1600, 1000)
            // distance from p to the ray
            val d = doubleArrayOf(p[0] - r[0], p[1] - r[1], p[2] - r[2])
            val t = d[0] * r[3] + d[1] * r[4] + d[2] * r[5]
            val off = doubleArrayOf(d[0] - r[3] * t, d[1] - r[4] * t, d[2] - r[5] * t)
            assertTrue("persp=$persp off=${off.toList()}", abs(off[0]) + abs(off[1]) + abs(off[2]) < 0.05)
            assertTrue(t > 0)
            // zooming at a pixel keeps the point under it
            val z = c.copy(perspective = false).zoom(2f, s[0], s[1], 1600, 1000)
            if (!persp) { val s2 = z.project(p, 1600, 1000)!!; assertEquals(s[0], s2[0], 0.5f); assertEquals(s[1], s2[1], 0.5f) }
        }
        // looking at the XY sketch plane shows +X to the right and +Y up
        val top = Camera().lookAt(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(1.0, 0.0, 0.0))
        val o = top.project(doubleArrayOf(0.0, 0.0, 0.0), 1000, 1000)!!; val x = top.project(doubleArrayOf(10.0, 0.0, 0.0), 1000, 1000)!!; val y = top.project(doubleArrayOf(0.0, 10.0, 0.0), 1000, 1000)!!
        assertTrue(x[0] > o[0] + 5 && abs(x[1] - o[1]) < 2)
        assertTrue(y[1] < o[1] - 5 && abs(y[0] - o[0]) < 2)
        val front = Camera().lookAt(doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0))
        val fx = front.project(doubleArrayOf(10.0, 0.0, 0.0), 1000, 1000)!!; val fz = front.project(doubleArrayOf(0.0, 0.0, 10.0), 1000, 1000)!!; val fo = front.project(doubleArrayOf(0.0, 0.0, 0.0), 1000, 1000)!!
        assertTrue(fx[0] > fo[0] + 5 && fz[1] < fo[1] - 5)
    }

    @Test fun formsBuildTheParametersTheEngineTakes() {
        val f = FormState(Tools.byId("move"))
        f.values["bodies"] = "Body1"; f.values["tx"] = "5"; f.values["ty"] = "w/2"; f.values["rot"] = "Z"; f.values["angle"] = "90 deg"
        val j = f.build().first!!
        assertEquals("[5,\"w/2\",0]", j.getJSONArray("translate").toString().replace("\\/", "/")); assertEquals("[0,0,1]", j.getJSONArray("axis").toString()); assertEquals("90 deg", j.getString("angle")); assertFalse(j.has("tx") || j.has("rot"))
        val h = FormState(Tools.byId("hole"))
        assertEquals("Position (tap a face) is needed", h.build().second)
        h.picks["position"] = listOf(Pick("face", "Body1", 3, doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.0, 0.0, 1.0)))
        h.values["cb_diameter"] = "9"
        val hj = h.build().first!!
        assertEquals(setOf("position", "diameter", "type"), hj.keys().asSequence().toSet()); assertEquals("[1,2,3]", hj.getJSONArray("position").toString())
        val p = FormState(Tools.byId("pattern_r")); p.values["features"] = "H1"
        val pj = p.build().first!!
        assertEquals(setOf("features", "dir1", "count1", "spacing1"), pj.keys().asSequence().toSet()); assertEquals("[1,0,0]", pj.getJSONArray("dir1").toString())
    }
}
