package com.lswlight.moneybook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CardMessageParserTest {

    private val now = LocalDateTime.of(2026, 10, 27, 18, 0)

    private fun parse(text: String, trusted: Boolean = false) =
        CardMessageParser.parse(text.trimIndent(), now, trusted)

    @Test
    fun shinhanSms() {
        val p = parse("[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/27 12:34 스타벅스 누적1,234,567원")!!
        assertEquals(12500L, p.amount)
        assertEquals("스타벅스", p.merchant)
        assertEquals("신한카드", p.card)
        assertEquals(LocalDateTime.of(2026, 10, 27, 12, 34), p.time)
        assertEquals(false, p.isCancel)
    }

    @Test
    fun hyundaiMultiline() {
        val p = parse(
            """
            [Web발신]
            현대카드 M 승인
            홍*동
            45,000원 일시불
            10/26 19:05
            이마트 성수점
            누적 1,234,567원
            """
        )!!
        assertEquals(45000L, p.amount)
        assertEquals("이마트 성수점", p.merchant)
        assertEquals("현대카드", p.card)
    }

    @Test
    fun kbSms() {
        val p = parse(
            """
            [Web발신]
            KB국민카드1*2*홍*동님
            10/27 08:10
            3,200원 일시불
            GS25 역삼점 사용
            누적 345,000원
            """
        )!!
        assertEquals(3200L, p.amount)
        assertEquals("GS25 역삼점", p.merchant)
        assertEquals("KB국민카드", p.card)
    }

    @Test
    fun samsungInstallment() {
        val p = parse("삼성1234승인 홍*동\n1,200,000원 3개월\n10/20 15:00 쿠팡\n누적2,000,000원")!!
        assertEquals(1200000L, p.amount)
        assertEquals("쿠팡", p.merchant)
    }

    @Test
    fun checkCardBalanceIsIgnored() {
        val p = parse("신한체크승인 홍*동 8,900원 10/27 12:00 김밥천국 잔액123,456원")!!
        assertEquals(8900L, p.amount)
        assertEquals("김밥천국", p.merchant)
        assertEquals("신한카드", p.card)
    }

    @Test
    fun kakaoAlimtalkLabeled() {
        val p = parse(
            """
            롯데카드
            [롯데카드] 승인 안내
            홍*동님
            카드: 롯데카드(2*3*)
            금액: 27,000원 일시불
            일시: 10/27 13:10
            가맹점: 배달의민족
            누적금액: 500,000원
            """
        )!!
        assertEquals(27000L, p.amount)
        assertEquals("배달의민족", p.merchant)
        assertEquals("롯데카드", p.card)
    }

    @Test
    fun cancel() {
        val p = parse("[Web발신]\n하나카드(1*2*)승인취소 홍*동님 12,500원 10/27 14:00 스타벅스")!!
        assertTrue(p.isCancel)
        assertEquals(12500L, p.amount)
        assertEquals("스타벅스", p.merchant)
        assertEquals("하나카드", p.card)
    }

    @Test
    fun appPushPipeStyle() {
        val p = parse("[우리카드] 15,000원 승인 | CU 강남점")!!
        assertEquals(15000L, p.amount)
        assertEquals("CU 강남점", p.merchant)
    }

    @Test
    fun tossStyleFromTrustedApp() {
        val p = parse("결제 알림\n스타벅스에서 6,500원 결제했어요", trusted = true)!!
        assertEquals(6500L, p.amount)
        assertEquals("스타벅스", p.merchant)
    }

    @Test
    fun lastYearDecemberReceivedInJanuary() {
        val jan = LocalDateTime.of(2027, 1, 1, 9, 0)
        val p = CardMessageParser.parse("신한카드(1234)승인 홍*동 10,000원(일시불)12/31 23:50 편의점", jan)!!
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 50), p.time)
    }

    @Test
    fun hanaPayOverseasYen() {
        // 하나카드 앱 알림 (앱 이름 "하나카드" 를 알림 서비스가 첫 줄로 붙여 준다)
        val p = parse(
            "하나카드\n결제\n(1*2*)이*우님 10/27 22:40/해외/승인/JPY 2,280/GOOGLE*YOUTUBEPREMIUM TOKYO      신용 결제로 처리되었습니다.",
            trusted = true,
        )!!
        assertEquals("JPY 2,280", p.foreign)
        assertEquals(Math.round(2280 * 9.3), p.amount)
        assertEquals("GOOGLE*YOUTUBEPREMIUM TOKYO", p.merchant)
        assertEquals("하나카드", p.card)
        assertEquals(LocalDateTime.of(2026, 10, 27, 22, 40), p.time)
        assertEquals("GOOGLE*YOUTUBEPREMIUM TOKYO (JPY 2,280 × 9.30원, 추정)", p.memo())
    }

    @Test
    fun overseasUsdWithDecimals() {
        val p = parse("[Web발신]\n신한카드(1234)해외승인 홍*동 USD 12.50 10/27 09:30 AMAZON.COM")!!
        assertEquals(17500L, p.amount)
        assertEquals("USD 12.50", p.foreign)
        assertEquals("AMAZON.COM", p.merchant)
    }

    @Test
    fun krwAmountWinsOverForeign() {
        val p = parse("현대카드 해외승인 홍*동\nUSD 10.00 (원화 13,950원)\n10/27 09:30\nNETFLIX")!!
        assertEquals(13950L, p.amount)
        assertEquals("", p.foreign)
    }

    @Test
    fun liveRateAndFee() {
        val p = CardMessageParser.parse(
            "하나카드\n결제\n(1*2*)이*우님 10/27 22:40/해외/승인/JPY 2,280/GOOGLE*YOUTUBEPREMIUM TOKYO 신용 결제로 처리되었습니다.",
            now, trustedSource = true, rates = mapOf("JPY" to 9.0), feePercent = 1.4,
        )!!
        assertEquals(Math.round(2280 * 9.0 * 1.014), p.amount)
        assertEquals("GOOGLE*YOUTUBEPREMIUM TOKYO (JPY 2,280 × 9.00원 + 수수료 1.4%, 추정)", p.memo())
    }

    @Test
    fun feeNotAppliedToDomestic() {
        val p = CardMessageParser.parse("신한카드(1234)승인 홍*동 10,000원(일시불)10/27 12:00 편의점", now, feePercent = 1.4)!!
        assertEquals(10000L, p.amount)
        assertEquals("편의점", p.memo())
    }

    @Test
    fun rateJsonOpenErApi() {
        val json = """{"result":"success","base_code":"KRW","rates":{"KRW":1,"JPY":0.1075,"USD":0.000714,"AED":0.0026}}"""
        val r = ExchangeRateJson.krwPerUnit(json)
        assertEquals(1 / 0.1075, r["JPY"]!!, 1e-9)
        assertEquals(1 / 0.000714, r["USD"]!!, 1e-6)
        assertNull(r["KRW"])   // 지원 통화가 아니면 버린다
        assertNull(r["AED"])
    }

    @Test
    fun rateJsonFawazahmed() {
        val json = """{"date":"2026-10-27","krw":{"1inch":0.003,"jpy":0.1075,"usd":7.14e-4}}"""
        val r = ExchangeRateJson.krwPerUnit(json)
        assertEquals(1 / 0.1075, r["JPY"]!!, 1e-9)
        assertEquals(1 / 7.14e-4, r["USD"]!!, 1e-6)
    }

    @Test
    fun rateJsonBroken() {
        assertTrue(ExchangeRateJson.krwPerUnit("<html>error</html>").isEmpty())
    }

    @Test
    fun ignoresNonPaymentMessages() {
        assertNull(parse("신한카드(1234) 승인거절 홍*동 12,500원 한도초과"))
        assertNull(parse("[신한카드] 11월 결제예정금액은 1,234,567원입니다"))
        assertNull(parse("(광고) 신한카드 결제 시 5,000원 할인"))
        assertNull(parse("친구: 어제 밥값 결제 15,000원 보낼게"))   // 카드 단어 없는 대화
        assertNull(parse("신한카드 인증번호 [123456]"))
    }
}
