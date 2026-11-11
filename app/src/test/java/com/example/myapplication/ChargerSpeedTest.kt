package com.example.myapplication

import com.example.myapplication.data.EvChargerClient
import com.example.myapplication.data.TmapChargerClient
import com.example.myapplication.model.ChargerSpeed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargerSpeedTest {
    @Test
    fun evChargerTypeAndOutput() {
        assertEquals(ChargerSpeed.SLOW, EvChargerClient.speedOf("02", 7))
        assertEquals(ChargerSpeed.SLOW, EvChargerClient.speedOf("08", null))
        assertEquals(ChargerSpeed.SLOW, EvChargerClient.speedOf("04", 11))
        assertEquals(ChargerSpeed.FAST, EvChargerClient.speedOf("04", 100))
        assertEquals(ChargerSpeed.FAST, EvChargerClient.speedOf("07", null))
        assertNull(EvChargerClient.speedOf("", null))
    }

    @Test
    fun tmapChargerFields() {
        assertEquals(ChargerSpeed.FAST, TmapChargerClient.speedOf(JSONObject("""{"isFast":"Y","type":"02"}""")))
        assertEquals(ChargerSpeed.SLOW, TmapChargerClient.speedOf(JSONObject("""{"isFast":"N"}""")))
        assertEquals(ChargerSpeed.FAST, TmapChargerClient.speedOf(JSONObject("""{"powerType":"급속(50kW)"}""")))
        assertEquals(ChargerSpeed.SLOW, TmapChargerClient.speedOf(JSONObject("""{"type":"02"}""")))
        assertNull(TmapChargerClient.speedOf(JSONObject("{}")))
    }
}
