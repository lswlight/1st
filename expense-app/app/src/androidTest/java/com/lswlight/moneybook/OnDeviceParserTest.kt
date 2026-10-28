package com.lswlight.moneybook

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 안드로이드의 정규식 엔진(ICU)은 PC 의 자바와 문법이 조금 달라서,
 * PC 단위 테스트를 통과해도 폰에서만 오류가 날 수 있다. 그래서 같은 해석을 기기에서 한 번 더 돌린다.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceParserTest {

    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)

    @Test
    fun unescapedClosingBraceIsRejectedOnDevice() {
        // 1.3~1.6 버전이 쓰던 패턴. 폰에서는 이 줄에서 오류가 나 해외 결제 처리가 멈췄다 (원인 확인용)
        val error = runCatching { Regex(""""(rates|krw)"\s*:\s*\{([^}]*)}""") }.exceptionOrNull()
        assertNotNull("ICU 가 짝 없는 } 를 허용함 — 다른 원인을 찾아야 함", error)
    }

    @Test
    fun rateJsonParsesOnDevice() {
        val open = ExchangeRateJson.krwPerUnit("""{"result":"success","rates":{"KRW":1,"JPY":0.1075,"USD":0.000714}}""")
        assertEquals(1 / 0.1075, open["JPY"]!!, 1e-9)
        val fawaz = ExchangeRateJson.krwPerUnit("""{"date":"2026-10-28","krw":{"jpy": 0.1075,"usd": 7.14e-4}}""")
        assertEquals(1 / 7.14e-4, fawaz["USD"]!!, 1e-6)
    }

    @Test
    fun overseasKakaoMessageOnDevice() {
        val p = CardMessageParser.parse(
            "홍길동\n하나카드 결제 (1*2*)이*우님 10/28 09:40/해외/승인/USD 9.99/NETFLIX.COM 신용 결제로 처리되었습니다.",
            now, rates = mapOf("USD" to 1430.0), feePercent = 2.0,
        )
        assertNotNull(p)
        assertEquals("NETFLIX.COM", p!!.merchant)
        assertTrue(p.memo().contains("USD 9.99"))
    }

    @Test
    fun domesticSmsOnDevice() {
        val p = CardMessageParser.parse("[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/27 12:34 스타벅스 누적1,234,567원", now)
        assertEquals(12500L, p!!.amount)
        assertEquals("스타벅스", p.merchant)
    }

    @Test
    fun benefitMinusOnDevice() {
        val p = CardMessageParser.parse("[신한카드] 커피 캐시백 -2,000원 적용 10/28 08:10 스타벅스", now)
        assertTrue(p!!.isBenefit)
        assertEquals(2000L, p.amount)
    }
}
