package com.example.myapplication.nav

import com.example.myapplication.data.TmapRouteClient
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.Station

/**
 * 충전소까지 가는 경로와 소요 시간을 TMAP(실시간 교통 반영)에서 받아 둔다.
 * API 호출을 아끼려고 가까운 충전소 몇 곳만, 한 번에 하나씩, 위치가 바뀌거나 시간이 지났을 때만 다시 받는다.
 */
class StationEtas(private val routeClient: TmapRouteClient) {

    /** [route]가 null이면 경로 조회에 실패한 것이다(키 없음, 네트워크 오류). */
    private class Entry(val route: NavRoute?, val from: GeoPoint, val at: Long)

    private val entries = HashMap<String, Entry>()
    private var loadingId: String? = null

    /** 받아 둔 실제 도로 경로. 없으면 null. */
    fun route(stationId: String): NavRoute? = entries[stationId]?.route

    /** 실시간 교통을 반영한 소요 시간(초). 경로를 아직 못 받았으면 null. */
    fun liveSeconds(stationId: String): Int? = entries[stationId]?.route?.totalSeconds

    /**
     * 가까운 [MAX_STATIONS]곳과 [selectedId]의 경로를 필요하면 하나 다시 받는다.
     * [onUpdated]는 새 경로를 받았을 때 메인 스레드에서 불린다.
     */
    fun refresh(car: GeoPoint, stations: List<Station>, selectedId: String?, now: Long, onUpdated: () -> Unit) {
        if (loadingId != null) return
        val ids = stations.map { it.id }.toSet()
        entries.keys.retainAll(ids)
        val targets = stations.sortedBy { car.distanceTo(it.position) }.take(MAX_STATIONS).toMutableList()
        // 선택한 충전소는 지도에 경로를 보여주므로 가장 먼저 받는다.
        stations.firstOrNull { it.id == selectedId }?.let {
            targets.remove(it)
            targets.add(0, it)
        }
        val target = targets.firstOrNull { isStale(entries[it.id], car, now) } ?: return
        loadingId = target.id
        routeClient.fetch(car, target.position) { route ->
            loadingId = null
            entries[target.id] = Entry(route, car, now)
            if (route != null) onUpdated()
        }
    }

    /** 위치가 바뀌면(충전소가 바뀌면) 지난 경로는 버린다. */
    fun clear() {
        entries.clear()
    }

    private fun isStale(entry: Entry?, car: GeoPoint, now: Long): Boolean {
        if (entry == null) return true
        val age = now - entry.at
        if (entry.route == null) return age >= RETRY_MS
        return age >= TRAFFIC_REFRESH_MS || entry.from.distanceTo(car) >= MOVE_REFRESH_METERS
    }

    companion object {
        /** 경로를 받을 가까운 충전소 수. */
        const val MAX_STATIONS = 5
        /** 실시간 교통이 바뀌므로 이 시간이 지나면 다시 받는다. */
        const val TRAFFIC_REFRESH_MS = 3 * 60_000L
        /** 차가 이만큼 움직이면 출발점이 달라졌으므로 다시 받는다. */
        const val MOVE_REFRESH_METERS = 300.0
        /** 실패한 경로는 이 시간 뒤에 다시 시도한다. */
        const val RETRY_MS = 60_000L

        /** 교통 정보가 없을 때 쓰는 추정값. 직선거리의 1.3배를 시속 30km로 달린다고 본다. */
        fun estimateSeconds(meters: Double): Int = (meters * 1.3 / (30_000.0 / 3600.0)).toInt()
    }
}
