package com.example.myapplication

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.ui.GoMapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 두 손가락 터치 이벤트를 만들어 지도에 넣어 본다. */
@RunWith(AndroidJUnit4::class)
class GoMapViewZoomTest {
    private lateinit var map: GoMapView
    private val centerX = 900f
    private val centerY = 600f

    @Before
    fun setUp() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        map = GoMapView(context)
        map.layout(0, 0, 1800, 1200)
    }

    @Test
    fun spreadingFingersZoomsIn() = onMain {
        val base = map.carRenderScale
        pinch(fromSpan = 200f, toSpan = 600f)
        assertEquals(1, map.zoomStep)
        assertTrue("zoom=${map.zoom}", map.zoom > GoMapView.DEFAULT_ZOOM)
        assertTrue("car=${map.carRenderScale}", map.carRenderScale > base)
    }

    @Test
    fun pinchingFingersZoomsOut() = onMain {
        val base = map.carRenderScale
        pinch(fromSpan = 600f, toSpan = 300f)
        assertEquals(-1, map.zoomStep)
        assertTrue("zoom=${map.zoom}", map.zoom < GoMapView.DEFAULT_ZOOM)
        assertTrue("car=${map.carRenderScale}", map.carRenderScale < base)
    }

    @Test
    fun zoomStopsAtTwoStepsInAndTwoStepsOut() = onMain {
        repeat(4) { pinch(fromSpan = 200f, toSpan = 1000f) }
        assertEquals(2, map.zoomStep)
        assertEquals(GoMapView.MAX_ZOOM, map.zoom, 0.01f)

        repeat(2) { pinch(fromSpan = 1000f, toSpan = 200f) }
        assertEquals(0, map.zoomStep)
        assertEquals(GoMapView.DEFAULT_ZOOM, map.zoom, 0.01f)

        repeat(4) { pinch(fromSpan = 1000f, toSpan = 200f) }
        assertEquals(-2, map.zoomStep)
        assertEquals(GoMapView.MIN_ZOOM, map.zoom, 0.01f)
    }

    @Test
    fun oneFingerDoesNotZoom() = onMain {
        val time = SystemClock.uptimeMillis()
        map.dispatchTouchEvent(event(time, time, MotionEvent.ACTION_DOWN, listOf(centerX to centerY)))
        map.dispatchTouchEvent(event(time, time + 50, MotionEvent.ACTION_MOVE, listOf(centerX + 300 to centerY)))
        map.dispatchTouchEvent(event(time, time + 100, MotionEvent.ACTION_UP, listOf(centerX + 300 to centerY)))
        assertEquals(GoMapView.DEFAULT_ZOOM, map.zoom, 0.001f)
    }

    @Test
    fun pinchKeepsCarInPlaceEvenAfterPanning() = onMain {
        drag(toX = centerX + 150, toY = centerY + 60)
        val x = map.carScreenX
        val y = map.carScreenY
        pinch(fromSpan = 200f, toSpan = 400f)
        assertTrue("zoom=${map.zoom}", map.zoom > GoMapView.DEFAULT_ZOOM)
        assertEquals(x, map.carScreenX, 1f)
        assertEquals(y, map.carScreenY, 1f)
        pinch(fromSpan = 600f, toSpan = 300f)
        assertEquals(x, map.carScreenX, 1f)
        assertEquals(y, map.carScreenY, 1f)
    }

    @Test
    fun oneFingerDragPansTheMap() = onMain {
        assertFalse(map.isPanned)
        drag(toX = centerX + 300, toY = centerY + 100)
        assertTrue(map.isPanned)
    }

    @Test
    fun tapAndPinchDoNotPan() = onMain {
        drag(toX = centerX + 2, toY = centerY)
        assertFalse(map.isPanned)
        pinch(fromSpan = 200f, toSpan = 600f)
        assertFalse(map.isPanned)
    }

    @Test
    fun rotateModeDragTurnsAroundCarWithoutPanning() = onMain {
        val x = map.carScreenX
        val y = map.carScreenY
        map.rotateMode = true
        drag(toX = centerX + 300, toY = centerY)
        // 지도 너비의 1/6을 오른쪽으로 끌면 60도 돈다.
        assertEquals(300f, map.bearing, 0.5f)
        assertFalse(map.isPanned)
        assertEquals(x, map.carScreenX, 0.5f)
        assertEquals(y, map.carScreenY, 0.5f)
    }

    @Test
    fun rotatingKeepsPannedCarInPlace() = onMain {
        drag(toX = centerX + 300, toY = centerY + 100)
        val x = map.carScreenX
        val y = map.carScreenY
        map.rotateMode = true
        drag(toX = centerX - 450, toY = centerY)
        assertEquals(90f, map.bearing, 0.5f)
        assertEquals(x, map.carScreenX, 0.5f)
        assertEquals(y, map.carScreenY, 0.5f)
    }

    @Test
    fun twoFingerTwistRotatesAndSmallWobbleDoesNot() = onMain {
        twist(degrees = 5f)
        assertEquals(0f, map.bearing, 0.001f)
        twist(degrees = 45f)
        // 시계 방향으로 비틀면 지도가 따라 돌도록 카메라는 반대로 돈다.
        assertEquals(360f - 45f, map.bearing, 9f)
    }

    private fun twist(degrees: Float) {
        val start = SystemClock.uptimeMillis()
        fun fingers(angle: Float): List<Pair<Float, Float>> {
            val radians = Math.toRadians(angle.toDouble())
            val dx = (200 * Math.cos(radians)).toFloat()
            val dy = (200 * Math.sin(radians)).toFloat()
            return listOf(centerX - dx to centerY - dy, centerX + dx to centerY + dy)
        }
        val pointerShift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        map.dispatchTouchEvent(event(start, start, MotionEvent.ACTION_DOWN, fingers(0f).take(1)))
        map.dispatchTouchEvent(event(start, start + 10, MotionEvent.ACTION_POINTER_DOWN or pointerShift, fingers(0f)))
        for (step in 1..STEPS) {
            map.dispatchTouchEvent(event(start, start + 10 + step * 16L, MotionEvent.ACTION_MOVE, fingers(degrees * step / STEPS)))
        }
        val end = start + 10 + (STEPS + 1) * 16L
        map.dispatchTouchEvent(event(start, end, MotionEvent.ACTION_POINTER_UP or pointerShift, fingers(degrees)))
        map.dispatchTouchEvent(event(start, end + 10, MotionEvent.ACTION_UP, fingers(degrees).take(1)))
    }

    private fun drag(toX: Float, toY: Float) {
        val time = SystemClock.uptimeMillis()
        map.dispatchTouchEvent(event(time, time, MotionEvent.ACTION_DOWN, listOf(centerX to centerY)))
        map.dispatchTouchEvent(event(time, time + 50, MotionEvent.ACTION_MOVE, listOf(toX to toY)))
        map.dispatchTouchEvent(event(time, time + 100, MotionEvent.ACTION_UP, listOf(toX to toY)))
    }

    @Test
    fun pinchIsNotTreatedAsTap() = onMain {
        var tapped = false
        map.onStationTapped = { tapped = true }
        pinch(fromSpan = 200f, toSpan = 210f)
        assertFalse(tapped)
    }

    private fun pinch(fromSpan: Float, toSpan: Float) {
        val start = SystemClock.uptimeMillis()
        fun fingers(span: Float) = listOf(centerX - span / 2 to centerY, centerX + span / 2 to centerY)
        map.dispatchTouchEvent(event(start, start, MotionEvent.ACTION_DOWN, fingers(fromSpan).take(1)))
        map.dispatchTouchEvent(
            event(start, start + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(fromSpan)),
        )
        for (step in 1..STEPS) {
            val span = fromSpan + (toSpan - fromSpan) * step / STEPS
            map.dispatchTouchEvent(event(start, start + 10 + step * 16L, MotionEvent.ACTION_MOVE, fingers(span)))
        }
        val end = start + 10 + (STEPS + 1) * 16L
        map.dispatchTouchEvent(
            event(start, end, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(toSpan)),
        )
        map.dispatchTouchEvent(event(start, end + 10, MotionEvent.ACTION_UP, fingers(toSpan).take(1)))
    }

    private fun event(down: Long, time: Long, action: Int, points: List<Pair<Float, Float>>): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            down, time, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val STEPS = 12
    }
}
