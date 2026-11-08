package com.example.myapplication

import com.example.myapplication.data.EvChargerClient
import com.example.myapplication.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class EvChargerClientTest {
    private val center = GeoPoint(35.8999, 128.8551)

    // 실제 응답 형식(XML)을 줄인 예시
    private val xml = """
        <response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
        <body><items>
          <item><statNm>대구대학교 전기차충전소</statNm><statId>ME000001</statId><chgerId>01</chgerId><chgerType>04</chgerType>
            <lat>35.8990</lat><lng>128.8540</lng><stat>3</stat><nowTsdt>20261108120000</nowTsdt><output>100</output><delYn>N</delYn></item>
          <item><statNm>대구대학교 전기차충전소</statNm><statId>ME000001</statId><chgerId>02</chgerId><chgerType>02</chgerType>
            <lat>35.8990</lat><lng>128.8540</lng><stat>2</stat><nowTsdt></nowTsdt><output>7</output><delYn>N</delYn></item>
          <item><statNm>대구대학교 전기차충전소</statNm><statId>ME000001</statId><chgerId>03</chgerId><chgerType>02</chgerType>
            <lat>35.8990</lat><lng>128.8540</lng><stat>9</stat><output>7</output><delYn>N</delYn></item>
          <item><statNm>삭제된 곳</statNm><statId>ME000002</statId><chgerId>01</chgerId><chgerType>02</chgerType>
            <lat>35.8991</lat><lng>128.8541</lng><stat>2</stat><delYn>Y</delYn></item>
          <item><statNm>먼 곳</statNm><statId>ME000003</statId><chgerId>01</chgerId><chgerType>02</chgerType>
            <lat>36.5</lat><lng>128.8541</lng><stat>2</stat><delYn>N</delYn></item>
        </items><numOfRows>9999</numOfRows><pageNo>1</pageNo><totalCount>5</totalCount></body></response>
    """.trimIndent()

    private fun kst(value: String) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }.parse(value)!!.time

    @Test
    fun `groups chargers into stations and skips unknown, deleted and far ones`() {
        val rows = EvChargerClient.parseChargers(xml)
        assertEquals(5, rows.size)
        val candidates = EvChargerClient.toCandidates(rows, center, kst("20261108121000"))
        assertEquals(1, candidates.size)
        val station = candidates.single()
        assertEquals("ev-ME000001", station.id)
        assertEquals("대구대학교", station.name)
        assertEquals(2, station.total)
        assertEquals(1, station.charging)
        assertEquals(listOf("01", "02"), station.chargerIds)
        // 급속 40분 중 10분이 지났다.
        assertEquals(30 * 60, station.remainingSeconds!![0])
        assertNull(station.remainingSeconds!![1])
    }

    @Test
    fun `status updates keep only known states`() {
        val updates = EvChargerClient.toUpdates(EvChargerClient.parseChargers(xml), kst("20261108121000"))
        assertEquals(4, updates.size)
        assertEquals(30 * 60, updates[0].remainingSeconds)
        assertNull(updates[1].remainingSeconds)
    }

    @Test
    fun `api errors are reported`() {
        val error = """
            <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>
            </cmmMsgHeader></OpenAPI_ServiceResponse>
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) { EvChargerClient.parseChargers(error) }
    }
}
