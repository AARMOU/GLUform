package com.gluform.app.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

interface ViewportHost {
    var camera: Camera
    /** One-finger drags belong to the active tool (drawing, dragging sketch geometry) instead of the camera. */
    fun toolOwnsDrag(x: Float, y: Float): Boolean
    fun dragStart(x: Float, y: Float); fun dragMove(x: Float, y: Float); fun dragEnd(x: Float, y: Float); fun dragCancel()
    fun tap(x: Float, y: Float); fun longPress(x: Float, y: Float); fun doubleTap(x: Float, y: Float)
    /** In a sketch, a one-finger drag on empty space pans instead of orbiting. */
    fun panWithOneFinger(): Boolean
    fun hover(x: Float, y: Float) {}
}

@SuppressLint("ViewConstructor")
class CadView(ctx: Context, val renderer: Renderer, private val host: ViewportHost) : GLSurfaceView(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var mode = 0 // 0 idle, 1 one finger, 2 two fingers, 3 done until all up
    private var sx = 0f; private var sy = 0f; private var lx = 0f; private var ly = 0f
    private var moved = false; private var toolDrag = false; private var downAt = 0L; private var lastTapAt = 0L; private var lastTapX = 0f; private var lastTapY = 0f
    private var span = 1f; private var cx = 0f; private var cy = 0f
    private val longPress = Runnable { if (mode == 1 && !moved) { mode = 3; host.longPress(sx, sy) } }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1; sx = e.x; sy = e.y; lx = sx; ly = sy; moved = false; toolDrag = false; downAt = e.eventTime
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (toolDrag) { host.dragCancel(); toolDrag = false }
                if (e.pointerCount >= 2) { mode = 2; span = dist(e); cx = (e.getX(0) + e.getX(1)) / 2; cy = (e.getY(0) + e.getY(1)) / 2 }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    if (!moved && hypot(e.x - sx, e.y - sy) > slop) {
                        moved = true; removeCallbacks(longPress)
                        toolDrag = host.toolOwnsDrag(sx, sy)
                        if (toolDrag) host.dragStart(sx, sy)
                    }
                    if (moved) {
                        if (toolDrag) host.dragMove(e.x, e.y)
                        else if (host.panWithOneFinger()) host.camera = host.camera.pan(e.x - lx, e.y - ly, height)
                        else host.camera = host.camera.orbit(e.x - lx, e.y - ly)
                    }
                    lx = e.x; ly = e.y
                } else if (mode == 2 && e.pointerCount >= 2) {
                    val ns = dist(e); val nx = (e.getX(0) + e.getX(1)) / 2; val ny = (e.getY(0) + e.getY(1)) / 2
                    var c = host.camera.pan(nx - cx, ny - cy, height)
                    if (span > 1f && ns > 1f) c = c.zoom(ns / span, nx, ny, width, height)
                    host.camera = c; span = ns; cx = nx; cy = ny
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { mode = 3 }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (mode == 1) {
                    if (toolDrag) host.dragEnd(e.x, e.y)
                    else if (!moved) {
                        val dbl = e.eventTime - lastTapAt < 300 && hypot(e.x - lastTapX, e.y - lastTapY) < slop * 3
                        if (dbl) { host.doubleTap(e.x, e.y); lastTapAt = 0 } else { host.tap(e.x, e.y); lastTapAt = e.eventTime; lastTapX = e.x; lastTapY = e.y }
                    }
                }
                mode = 0; toolDrag = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); if (toolDrag) host.dragCancel(); mode = 0; toolDrag = false }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            host.camera = host.camera.zoom(if (v > 0) 1.15f else 1 / 1.15f, e.x, e.y, width, height)
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) host.hover(e.x, e.y)
        return super.onGenericMotionEvent(e)
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
}
