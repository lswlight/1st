package com.lswlight.moneybook

import java.time.LocalDateTime

/** 카드 승인/취소 알림 한 건을 해석한 결과. */
data class ParsedPayment(
    val amount: Long,          // 원 단위, 항상 양수
    val isCancel: Boolean,     // 승인 취소(환불)면 true
    val merchant: String,      // 가맹점 (못 찾으면 "카드 결제")
    val card: String,          // 카드사 (못 찾으면 빈 문자열)
    val time: LocalDateTime?,  // 메시지에 적힌 결제 시각 (없으면 null)
    val foreign: String = "",  // 해외 결제의 원래 금액 (예: "JPY 2,280"), 이때 amount 는 원화 환산 추정치
) {
    /** 내역에 남길 내용. 해외 결제면 원래 금액을 함께 적는다. */
    fun memo() = if (foreign.isEmpty()) merchant else "$merchant ($foreign, 환산 추정)"
}

/**
 * 카드사 문자, 카카오톡 알림톡, 카드 앱 푸시 문구에서 금액·가맹점·카드사를 뽑는다.
 * 안드로이드 의존성이 없어서 JVM 단위 테스트로 바로 검증할 수 있다.
 */
object CardMessageParser {

    private val AMOUNT = Regex("""(?<![\d,.])(\d{1,3}(?:,\d{3})+|\d+)\s*원""")
    private val DATE_TIME = Regex("""(\d{1,2})\s*[/.월]\s*(\d{1,2})\s*일?\s+(\d{1,2}):(\d{2})""")
    private val CARD = Regex(
        """(KB국민|국민|신한|현대|삼성|롯데|하나|우리|NH농협|농협|NH|BC|비씨|씨티|IBK기업|IBK|기업|카카오뱅크|토스뱅크|토스|케이뱅크|수협|광주|전북|제주)\s*(?:카드|체크)"""
    )
    // "삼성1234승인" 처럼 카드 단어 없이 카드사 이름 뒤에 카드번호가 붙는 문자
    private val CARD_SHORT = Regex("""(KB국민|국민|신한|현대|삼성|롯데|하나|우리|NH|농협|BC|씨티)\d""")
    // 해외 결제 금액: "JPY 2,280", "USD 12.50"
    private val FOREIGN = Regex("""(?<![A-Z])(USD|JPY|EUR|CNY|GBP|HKD|SGD|AUD|CAD|CHF|THB|VND|TWD|PHP|NZD|MYR|IDR)\s*(\d{1,3}(?:,\d{3})*(?:\.\d+)?|\d+(?:\.\d+)?)""")

    // 외화 1단위당 대략적인 원화 (인터넷 권한이 없어 실시간 환율 대신 쓰는 추정치)
    private val KRW_PER_UNIT = mapOf(
        "USD" to 1400.0, "JPY" to 9.3, "EUR" to 1600.0, "CNY" to 195.0, "GBP" to 1850.0,
        "HKD" to 180.0, "SGD" to 1080.0, "AUD" to 920.0, "CAD" to 1010.0, "CHF" to 1750.0,
        "THB" to 42.0, "VND" to 0.055, "TWD" to 45.0, "PHP" to 24.0, "NZD" to 830.0,
        "MYR" to 330.0, "IDR" to 0.085,
    )

    private val MERCHANT_LABEL = Regex("""(?:이용가맹점|가맹점명|가맹점|사용처|이용처)\s*[:：]?\s*([^\n]+)""")
    private val TOSS_STYLE = Regex("""^(.+?)에서\s*[\d,]+\s*원""", RegexOption.MULTILINE)

    // 이 단어 바로 뒤의 금액은 결제 금액이 아니다 (누적 사용액, 잔액, 한도 등)
    private val NOT_PAYMENT_PREFIX = Regex("""(누적|잔액|한도|포인트|적립|캐시백|할인|청구)[^\n\d]{0,6}$""")

    // 결제가 아닌 안내 문자 (거절, 청구서, 광고 등)
    private val EXCLUDE = Regex("""승인\s*거절|거절|결제\s*예정|결제\s*대금|청구\s*금액|명세서|연체|이용\s*대금|출금\s*예정|\(광고\)|인증번호""")
    private val PAYMENT_WORD = Regex("""승인|결제|사용|일시불|할부|취소""")
    private val CARD_CONTEXT = Regex("""카드|체크|\[KB]|[Pp]ay|페이""")

    /**
     * @param text 알림 제목과 본문을 줄바꿈으로 이은 문자열
     * @param now  연도를 정하는 기준 시각
     * @param trustedSource 카드사 앱처럼 결제 알림만 보내는 앱이면 true (카드 단어가 없어도 인정)
     */
    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(), trustedSource: Boolean = false): ParsedPayment? {
        val body = text.replace("\r", "").lines().joinToString("\n") { it.trim() }.trim()
        if (body.isEmpty() || EXCLUDE.containsMatchIn(body)) return null
        if (!PAYMENT_WORD.containsMatchIn(body)) return null
        if (!trustedSource && !CARD_CONTEXT.containsMatchIn(body) && !CARD_SHORT.containsMatchIn(body)) return null

        // 원화 금액이 있으면 그대로, 없으면 외화 금액을 원화로 환산한다
        val krw = findAmount(body)
        val foreign = if (krw == null) FOREIGN.find(body) else null
        val amount = krw ?: foreign?.let { toKrw(it.groupValues[1], it.groupValues[2]) } ?: return null
        val card = (CARD.find(body) ?: CARD_SHORT.find(body))?.let { normalizeCard(it.groupValues[1]) }
            ?: if (body.contains("[KB]")) "KB국민카드" else ""

        return ParsedPayment(
            amount = amount,
            isCancel = body.contains("취소"),
            merchant = findMerchant(body).ifEmpty { "카드 결제" },
            card = card,
            time = findTime(body, now),
            foreign = foreign?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }.orEmpty(),
        )
    }

    private fun toKrw(currency: String, value: String): Long? {
        val v = value.replace(",", "").toDoubleOrNull() ?: return null
        val krw = Math.round(v * (KRW_PER_UNIT[currency] ?: return null))
        return if (krw > 0) krw else null
    }

    private fun findAmount(body: String): Long? {
        for (m in AMOUNT.findAll(body)) {
            val before = body.substring(0, m.range.first).substringAfterLast('\n')
            if (NOT_PAYMENT_PREFIX.containsMatchIn(before)) continue
            val value = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            if (value > 0) return value
        }
        return null
    }

    private fun findTime(body: String, now: LocalDateTime): LocalDateTime? {
        val m = DATE_TIME.find(body) ?: return null
        val (month, day, hour, minute) = m.destructured.toList().map { it.toInt() }
        return try {
            val t = LocalDateTime.of(now.year, month, day, hour, minute)
            // 1월에 받은 12월 결제 문자처럼 미래로 계산되면 작년으로 본다
            if (t.isAfter(now.plusDays(1))) t.minusYears(1) else t
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    private fun findMerchant(body: String): String {
        MERCHANT_LABEL.find(body)?.let { return clean(it.groupValues[1]) }
        TOSS_STYLE.find(body)?.let { return clean(it.groupValues[1]) }

        // "10/27 12:34 스타벅스" 처럼 시각 뒤에 가맹점이 붙는 문자
        DATE_TIME.find(body)?.let { m ->
            val rest = clean(body.substring(m.range.last + 1).substringBefore('\n'))
            if (rest.isNotEmpty()) return rest
        }

        // 나머지는 줄마다 군더더기를 지우고 마지막으로 남는 글자를 가맹점으로 본다
        return body.lines().map { clean(it) }.lastOrNull { it.isNotEmpty() } ?: ""
    }

    private val CLEAN_PATTERNS = listOf(
        Regex("""\[[^\]]*]"""),                    // [Web발신], [신한카드]
        Regex("""(누적|잔액|총누적).*"""),           // 누적 1,234,567원 이후 전부
        DATE_TIME,
        Regex("""\d{1,2}[/.]\d{1,2}"""),          // 날짜만 있는 경우
        Regex("""\d{1,2}:\d{2}(:\d{2})?"""),       // 시각만 있는 경우
        AMOUNT,
        FOREIGN,
        Regex("""\(?일시불\)?|\(?\d+\s*개월\)?|할부|전자금융"""),
        Regex("""\S*(카드|체크)\S*"""),              // 신한카드(1234)승인
        Regex("""\S*${CARD_SHORT.pattern}\S*"""),  // 삼성1234승인
        Regex("""\(?\d+\*[\d*]*\)?"""),            // 카드번호 1*2*, (1*2*)
        Regex("""\S*[가-힣]\*[가-힣]?\S*"""),          // 홍*동님 (GOOGLE*YOUTUBE 같은 가맹점은 남긴다)
        Regex("""\S+님"""),
        Regex("""결제로\s*처리되었습니다|처리되었습니다|해외|국내|신용"""),
        Regex("""승인|취소|사용|결제|완료|되었습니다|했어요"""),
        Regex("""\(\d*\)"""),
        Regex("""/"""),                            // 하나카드 앱: "해외/승인/JPY 2,280/가맹점"
    )

    private fun clean(s: String): String {
        var out = s
        for (p in CLEAN_PATTERNS) out = p.replace(out, " ")
        out = out.replace(Regex("""\s+"""), " ").trim().trim('-', ':', '：', '|', '/', '·', ',', '.', ' ')
        // 한글이나 영문이 한 글자도 없으면 가맹점이 아니다
        return if (out.any { it.isLetter() }) out else ""
    }

    private fun normalizeCard(name: String) = when (name) {
        "국민", "KB국민" -> "KB국민카드"
        "농협", "NH", "NH농협" -> "NH농협카드"
        "비씨", "BC" -> "BC카드"
        "IBK", "기업", "IBK기업" -> "IBK기업카드"
        else -> "${name}카드"
    }
}
