package com.example.myapplication

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.ui.GoMapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실행 중인 앱 창에 시스템 입력 경로로 두 손가락 제스처를 보낸다. */
@RunWith(AndroidJUnit4::class)
class PinchZoomOnScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pinchChangesZoomInRunningApp() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        val map = activity.findViewById<GoMapView>(R.id.map)
        SystemClock.sleep(4_000)
        capture("zoom_default")

        pinch(250f, 800f)
        SystemClock.sleep(800)
        assertTrue("zoom=${map.zoom}", map.zoom > 2f)
        capture("zoom_in")

        pinch(800f, 250f)
        pinch(800f, 250f)
        SystemClock.sleep(800)
        assertTrue("zoom=${map.zoom}", map.zoom < 1f)
        capture("zoom_out")

        pinch(800f, 250f)
        pinch(800f, 250f)
        SystemClock.sleep(300)
        assertEquals(0.5f, map.zoom, 0.001f)
    }

    /** holdMs 인자를 주면 각 단계에서 멈춰, 그동안 밖에서 화면을 캡처할 수 있게 한다. */
    private fun capture(name: String) {
        val hold = InstrumentationRegistry.getArguments().getString("holdMs")?.toLong() ?: return
        Log.i("PinchZoomOnScreenTest", "READY $name")
        SystemClock.sleep(hold)
    }

    private fun pinch(fromSpan: Float, toSpan: Float) {
        val start = SystemClock.uptimeMillis()
        val pointerShift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        fun fingers(span: Float) = listOf(CENTER_X - span / 2 to CENTER_Y, CENTER_X + span / 2 to CENTER_Y)
        send(start, start, MotionEvent.ACTION_DOWN, fingers(fromSpan).take(1))
        send(start, start + 10, MotionEvent.ACTION_POINTER_DOWN or pointerShift, fingers(fromSpan))
        for (step in 1..STEPS) {
            send(start, start + 10 + step * 16L, MotionEvent.ACTION_MOVE, fingers(fromSpan + (toSpan - fromSpan) * step / STEPS))
        }
        val end = start + 10 + (STEPS + 1) * 16L
        send(start, end, MotionEvent.ACTION_POINTER_UP or pointerShift, fingers(toSpan))
        send(start, end + 10, MotionEvent.ACTION_UP, fingers(toSpan).take(1))
    }

    private fun send(down: Long, time: Long, action: Int, points: List<Pair<Float, Float>>) {
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
        val event = MotionEvent.obtain(
            down, time, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private companion object {
        const val CENTER_X = 1450f
        const val CENTER_Y = 850f
        const val STEPS = 24
    }
}
