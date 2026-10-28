package com.lswlight.moneybook

/**
 * 무료 환율 서비스 응답(JSON)에서 "외화 1단위당 원화" 표를 만든다.
 * 안드로이드 의존성이 없어 JVM 단위 테스트로 검증한다.
 *
 * 지원 형식 (둘 다 원화 기준, 값은 "1원당 외화"):
 *  - open.er-api.com/v6/latest/KRW        → {"rates":{"JPY":0.107,...}}
 *  - @fawazahmed0/currency-api krw.json   → {"date":"...","krw":{"jpy":0.107,...}}
 */
object ExchangeRateJson {
    // 안드로이드 정규식(ICU)은 짝 없는 } 를 문법 오류로 보므로 반드시 \} 로 쓴다
    private val BLOCK = Regex(""""(rates|krw)"\s*:\s*\{([^}]*)\}""")
    private val PAIR = Regex(""""([A-Za-z]{3})"\s*:\s*([0-9.eE+-]+)""")

    fun krwPerUnit(json: String): Map<String, Double> {
        val block = BLOCK.find(json)?.groupValues?.get(2) ?: return emptyMap()
        val out = mutableMapOf<String, Double>()
        for (m in PAIR.findAll(block)) {
            val code = m.groupValues[1].uppercase()
            val perKrw = m.groupValues[2].toDoubleOrNull() ?: continue
            if (code in CardMessageParser.CURRENCIES && perKrw > 0) out[code] = 1 / perKrw
        }
        return out
    }
}
