package com.lswlight.moneybook

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 해외 결제 환산용 환율. 무료 공개 서비스에서 하루 한 번만 받아 폰에 저장해 둔다.
 * 보내는 것은 환율표 요청뿐이고, 결제 내역은 밖으로 나가지 않는다.
 */
object ExchangeRates {
    private const val PREFS = "exchange_rates"
    private const val TAG = "MoneyBook"
    private const val DEFAULT_FEE_PERCENT = 2f

    // 첫 주소가 안 되면 다음 주소를 쓴다 (둘 다 가입·키 없이 쓰는 공개 서비스)
    private val SOURCES = listOf(
        "https://open.er-api.com/v6/latest/KRW",
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/krw.json",
    )

    /** 저장해 둔 환율 (외화 1단위당 원화). 없으면 빈 표. */
    fun cached(context: Context): Map<String, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CardMessageParser.CURRENCIES.mapNotNull { code ->
            prefs.getString(code, null)?.toDoubleOrNull()?.let { code to it }
        }.toMap()
    }

    /** 환율을 받은 날짜. 한 번도 못 받았으면 null. */
    fun updatedOn(context: Context): LocalDate? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("date", null)?.let(LocalDate::parse)

    fun isStale(context: Context) = updatedOn(context) != LocalDate.now()

    /** 해외 이용 수수료율 (%). 기본 2 (넉넉하게 잡은 값, 화면에서 바꿀 수 있음). */
    fun feePercent(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("fee", DEFAULT_FEE_PERCENT).toDouble()

    fun setFeePercent(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("fee", value.toFloat()).apply()
    }

    /**
     * 인터넷에서 환율을 받아 저장한다. 성공하면 true.
     * 네트워크를 쓰므로 반드시 화면(메인) 스레드가 아닌 곳에서 부른다.
     */
    fun refresh(context: Context): Boolean {
        for (source in SOURCES) {
            val rates = try {
                ExchangeRateJson.krwPerUnit(download(source))
            } catch (e: Throwable) {
                Log.w(TAG, "환율 받기 실패: $source", e)
                continue
            }
            if (rates.isEmpty()) continue
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            rates.forEach { (code, krw) -> edit.putString(code, krw.toString()) }
            edit.putString("date", LocalDate.now().toString()).apply()
            return true
        }
        return false
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
