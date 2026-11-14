package com.lswlight.moneybook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * 감시 대상 앱(문자, 카카오톡, 카드 앱)에서 받은 최근 알림과 처리 결과를 남긴다.
 * 자동 기록이 안 될 때 앱이 알림을 아예 못 받았는지, 받았는데 못 읽었는지 확인하는 용도.
 */
object DetectLog {
    private const val PREFS = "detect_log"
    private const val KEY = "items"
    private const val MAX = 50

    /** 읽지 않는 앱에서 온 결제 같은 알림에 붙는 결과 문구 (이 항목을 누르면 그 앱을 추가할 수 있다) */
    const val NOT_WATCHED = "읽지 않는 앱이라 건너뜀"

    data class Item(val time: LocalDateTime, val app: String, val result: String, val text: String, val pkg: String = "")

    @Synchronized
    fun add(context: Context, pkg: String, result: String, text: String, label: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString(KEY, "[]"))
        val out = JSONArray().put(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("app", label ?: appName(pkg))
                .put("pkg", pkg)
                .put("result", result)
                .put("text", text.take(300))
        )
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun list(context: Context): List<Item> {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getLong("time").toLocalDateTime(), o.getString("app"), o.getString("result"), o.getString("text"), o.optString("pkg"))
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun appName(pkg: String) = when (pkg) {
        "com.kakao.talk" -> "카카오톡"
        "com.samsung.android.messaging" -> "삼성 메시지"
        "com.google.android.apps.messaging" -> "Google 메시지"
        "viva.republica.toss" -> "토스"
        "com.kakaopay.app" -> "카카오페이"
        "com.nhn.android.search" -> "네이버"
        "com.naverfin.payapp" -> "네이버페이"
        else -> pkg
    }
}
