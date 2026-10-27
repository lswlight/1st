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
    private const val MAX = 30

    data class Item(val time: LocalDateTime, val app: String, val result: String, val text: String)

    @Synchronized
    fun add(context: Context, pkg: String, result: String, text: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString(KEY, "[]"))
        val out = JSONArray().put(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("app", appName(pkg))
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
            Item(o.getLong("time").toLocalDateTime(), o.getString("app"), o.getString("result"), o.getString("text"))
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
        else -> pkg
    }
}
