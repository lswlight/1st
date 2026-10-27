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
    fun ignoresNonPaymentMessages() {
        assertNull(parse("신한카드(1234) 승인거절 홍*동 12,500원 한도초과"))
        assertNull(parse("[신한카드] 11월 결제예정금액은 1,234,567원입니다"))
        assertNull(parse("(광고) 신한카드 결제 시 5,000원 할인"))
        assertNull(parse("친구: 어제 밥값 결제 15,000원 보낼게"))   // 카드 단어 없는 대화
        assertNull(parse("신한카드 인증번호 [123456]"))
    }
}
