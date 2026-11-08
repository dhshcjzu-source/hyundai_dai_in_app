package com.example.myapplication.nav

import com.example.myapplication.model.GeoPoint

/** 길 안내 한 건. [distance]는 출발점부터 이 지점까지 경로를 따라 잰 거리(미터)다. */
data class Guidance(
    val distance: Double,
    val instruction: String,
    val road: String,
    /** 회전 방향. 직진 0, 오른쪽 +, 왼쪽 -. 경로 모양에서 계산한다. */
    val turnDegrees: Float,
    val arrival: Boolean,
)

/** 도로를 따라가는 주행 경로. 점 사이는 직선으로 잇는다. */
class NavRoute private constructor(
    val points: List<GeoPoint>,
    val guidance: List<Guidance>,
    /** 실제 도로 기준 예상 소요 시간(초). */
    val totalSeconds: Int,
    val cumulative: DoubleArray,
) {
    val totalMeters: Double get() = cumulative.last()

    fun positionAt(distance: Double): GeoPoint {
        val (index, t) = locate(distance)
        val a = points[index]
        val b = points[index + 1]
        return GeoPoint(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )
    }

    /** [distance] 지점에서 진행하는 방향(북=0, 동=90). */
    fun headingAt(distance: Double): Float {
        var index = locate(distance).first
        // 길이가 0인 구간은 방향이 없으므로 다음 구간을 본다.
        while (index < points.size - 2 && cumulative[index + 1] - cumulative[index] < MIN_SEGMENT_METERS) index++
        return points[index].bearingTo(points[index + 1])
    }

    /** 아직 지나지 않은 가장 가까운 안내. */
    fun nextGuidance(distance: Double): Guidance? = guidance.firstOrNull { it.distance > distance + 0.5 }

    private fun locate(distance: Double): Pair<Int, Double> {
        val d = distance.coerceIn(0.0, totalMeters)
        var index = cumulative.indexOfFirst { it >= d } - 1
        index = index.coerceIn(0, points.size - 2)
        val length = cumulative[index + 1] - cumulative[index]
        val t = if (length <= 0.0) 0.0 else (d - cumulative[index]) / length
        return index to t.coerceIn(0.0, 1.0)
    }

    /** 경로 점 [vertex]에서 붙는 안내. */
    data class RawGuidance(val vertex: Int, val instruction: String, val road: String, val arrival: Boolean)

    companion object {
        private const val MIN_SEGMENT_METERS = 1.0
        private const val TURN_LOOK_METERS = 8.0

        fun of(points: List<GeoPoint>, raw: List<RawGuidance>, totalSeconds: Int): NavRoute {
            require(points.size >= 2) { "경로 점이 2개 이상 필요하다" }
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + points[i - 1].distanceTo(points[i])
            val guidance = raw.map { item ->
                Guidance(
                    distance = cumulative[item.vertex],
                    instruction = item.instruction,
                    road = item.road,
                    turnDegrees = if (item.arrival) 0f else turnAt(points, cumulative, item.vertex),
                    arrival = item.arrival,
                )
            }
            return NavRoute(points, guidance, totalSeconds, cumulative)
        }

        /** 길이 없는 경우를 위한 직선 경로. 시속 40km로 잡고 시간을 계산한다. */
        fun straight(from: GeoPoint, to: GeoPoint): NavRoute {
            val meters = from.distanceTo(to)
            return of(listOf(from, to), listOf(RawGuidance(1, "목적지 도착", "", true)), (meters / 11.1).toInt())
        }

        /** 점 앞뒤로 조금 떨어진 점을 써서 들어오는 방향과 나가는 방향의 차이를 구한다. */
        private fun turnAt(points: List<GeoPoint>, cumulative: DoubleArray, vertex: Int): Float {
            var before = vertex
            while (before > 0 && cumulative[vertex] - cumulative[before] < TURN_LOOK_METERS) before--
            var after = vertex
            while (after < points.size - 1 && cumulative[after] - cumulative[vertex] < TURN_LOOK_METERS) after++
            if (before == vertex || after == vertex) return 0f
            val incoming = points[before].bearingTo(points[vertex])
            val outgoing = points[vertex].bearingTo(points[after])
            var delta = outgoing - incoming
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            return delta
        }
    }
}
