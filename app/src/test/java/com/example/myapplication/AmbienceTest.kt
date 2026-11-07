package com.example.myapplication

import com.example.myapplication.model.Ambience
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.SunPosition
import com.example.myapplication.model.Weather
import com.example.myapplication.model.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AmbienceTest {
    private val seoul = GeoPoint(37.57, 126.98)
    private fun sunAt(time: String) = SunPosition.at(seoul, ZonedDateTime.parse(time).toInstant().toEpochMilli())
    private val noon = SunPosition(50.0, 0.0)
    private val midnight = SunPosition(-40.0, 180.0)

    @Test
    fun `seoul sun is high around noon and below the horizon at midnight`() {
        // 11월 초 서울의 남중 고도는 약 37도다.
        assertEquals(37.0, sunAt("2026-11-04T12:25:00+09:00").elevation, 2.0)
        assertTrue(sunAt("2026-11-05T00:00:00+09:00").elevation < -30)
    }

    @Test
    fun `seoul sunset falls in the early evening`() {
        // 11월 초 서울의 일몰은 17시 35분 무렵이다.
        assertTrue(sunAt("2026-11-04T17:00:00+09:00").elevation > 0)
        assertTrue(sunAt("2026-11-04T18:00:00+09:00").elevation < 0)
    }

    @Test
    fun `hour angle is negative in the morning and positive in the afternoon`() {
        assertTrue(sunAt("2026-11-04T09:00:00+09:00").hourAngle < 0)
        assertTrue(sunAt("2026-11-04T15:00:00+09:00").hourAngle > 0)
    }

    @Test
    fun `weather codes map to kinds`() {
        assertEquals(WeatherKind.CLEAR, Weather.kindOf(0))
        assertEquals(WeatherKind.CLOUDY, Weather.kindOf(3))
        assertEquals(WeatherKind.FOG, Weather.kindOf(45))
        assertEquals(WeatherKind.RAIN, Weather.kindOf(63))
        assertEquals(WeatherKind.RAIN, Weather.kindOf(81))
        assertEquals(WeatherKind.SNOW, Weather.kindOf(75))
        assertEquals(WeatherKind.THUNDER, Weather.kindOf(95))
    }

    @Test
    fun `night is darker than day and shows stars and moon`() {
        val day = Ambience.of(noon, Weather.preset(WeatherKind.CLEAR))
        val night = Ambience.of(midnight, Weather.preset(WeatherKind.CLEAR))
        assertTrue(Ambience.luminance(night.ground) < Ambience.luminance(day.ground))
        assertTrue(night.stars > 0.5f && night.moon > 0f && night.sun == 0f)
        assertTrue(day.sun > 0f && day.stars == 0f && day.moon == 0f)
        assertTrue(night.headlights > 0.9f && day.headlights == 0f)
    }

    @Test
    fun `clouds and rain darken the day`() {
        val clear = Ambience.of(noon, Weather.preset(WeatherKind.CLEAR))
        val rain = Ambience.of(noon, Weather.preset(WeatherKind.RAIN))
        assertTrue(Ambience.luminance(rain.ground) < Ambience.luminance(clear.ground))
        assertTrue(rain.rain > 0f && clear.rain == 0f)
    }

    @Test
    fun `snow covers the ground`() {
        val clear = Ambience.of(noon, Weather.preset(WeatherKind.CLEAR))
        val snow = Ambience.of(noon, Weather.preset(WeatherKind.SNOW))
        assertTrue(Ambience.luminance(snow.ground) > Ambience.luminance(clear.ground))
        assertTrue(snow.snow > 0f)
    }

    @Test
    fun `thunder has lightning and unknown weather has none`() {
        assertTrue(Ambience.of(noon, Weather.preset(WeatherKind.THUNDER)).lightning)
        assertFalse(Ambience.of(noon, null).lightning)
    }
}
