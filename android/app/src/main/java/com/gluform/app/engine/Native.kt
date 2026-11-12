package com.gluform.app.engine

/** The SolidCraft engine, through the Rust bridge (libcadbridge.so). */
object Native {
    init { System.loadLibrary("cadbridge") }
    @JvmStatic external fun execute(command: String, paramsJson: String): String
    @JvmStatic external fun status(): String
    @JvmStatic external fun scene(): ByteArray
    @JvmStatic external fun pick(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, tol0: Double, tolK: Double): String
    @JvmStatic external fun sketchCurves(): String
    @JvmStatic external fun reset()
}
