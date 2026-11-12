package com.gluform.app.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    @Test
    fun invalidViewportsDoNotProduceCameraCoordinates() {
        for (perspective in listOf(false, true)) {
            val camera = Camera(perspective = perspective)
            for ((width, height) in listOf(0 to 100, -1 to 100, 100 to 0, 100 to -1)) {
                assertNull(camera.project(doubleArrayOf(0.0, 0.0, 0.0), width, height))
                val ray = camera.ray(0f, 0f, width, height)
                assertArrayEquals(DoubleArray(6), ray, 0.0)
                assertTrue(ray.all { it.isFinite() })
                assertEquals(camera, camera.zoom(2f, 0f, 0f, width, height))
                assertEquals(camera, camera.pan(1f, 1f, height))
            }
        }
    }

    @Test
    fun invalidZoomFactorsLeaveCameraUnchanged() {
        val camera = Camera()
        for (factor in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(camera, camera.zoom(factor, 400f, 300f, 800, 600))
        }
    }

    @Test
    fun validCameraInputsRemainFiniteAndZoomChangesDistance() {
        for (perspective in listOf(false, true)) {
            val camera = Camera(perspective = perspective)
            val ray = camera.ray(400f, 300f, 800, 600)
            assertTrue(ray.all { it.isFinite() })
            assertEquals(1.0, ray.slice(3..5).sumOf { it * it }, 1e-12)
            val zoomed = camera.zoom(2f, 400f, 300f, 800, 600)
            assertEquals(camera.distance / 2, zoomed.distance, 1e-12)
            assertTrue(zoomed.tx.isFinite() && zoomed.ty.isFinite() && zoomed.tz.isFinite())
        }
    }
}
