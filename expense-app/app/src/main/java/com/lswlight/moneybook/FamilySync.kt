package com.lswlight.moneybook

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.YearMonth
import java.util.concurrent.Executors

/**
 * 가족 가계부 공유. 자동가계부 공유 서버(sync-server/worker.js, Cloudflare Workers)에 '가족 방'을 만들고
 * 내역을 저장·수정·삭제할 때마다 올린다 (실패하면 쌓아 두었다가 다음에 다시 보낸다).
 *  - 쓰기 키: 이 폰(과 초대받은 안드로이드 가족)만 가진 비밀번호. 내역을 올리고 지울 수 있다
 *  - 보기 토큰: 보기 링크(/v/…)에 들어가는 값. 아이폰·PC 가족은 이 링크로 웹에서 본다
 */
object FamilySync {
    private const val PREFS = "family_sync"
    private const val TAG = "MoneyBook"
    private const val CODE_PREFIX = "MBSHARE2:"

    /** 공유 서버 주소. 배포한 뒤 여기에 넣는다 (공유 설정의 '서버 주소'에서도 바꿀 수 있다). */
    const val DEFAULT_SERVER = ""

    private val worker = Executors.newSingleThreadExecutor()

    data class Shared(val uid: String, val owner: String, val kind: Kind, val amount: Long, val memo: String, val card: String, val time: Long)

    // ---------------------------------------------------------------- 설정

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(c: Context) = prefs(c).getString("server", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
    fun owner(c: Context) = prefs(c).getString("owner", "").orEmpty()
    fun uploadOn(c: Context) = prefs(c).getBoolean("upload", true)
    private fun writeKey(c: Context) = prefs(c).getString("write", "").orEmpty()
    private fun viewToken(c: Context) = prefs(c).getString("view", "").orEmpty()

    fun hasServer(c: Context) = server(c).startsWith("https://")
    fun configured(c: Context) = hasServer(c) && viewToken(c).isNotEmpty()
    fun canWrite(c: Context) = configured(c) && writeKey(c).isNotEmpty()

    fun setServer(c: Context, url: String) = prefs(c).edit().putString("server", url.trim().trimEnd('/')).apply()
    fun setOwner(c: Context, name: String) = prefs(c).edit().putString("owner", name.trim()).apply()
    fun setUpload(c: Context, on: Boolean) = prefs(c).edit().putBoolean("upload", on).apply()

    /** 아이폰·PC 가족이 여는 보기 링크. */
    fun viewLink(c: Context) = "${server(c)}/v/${viewToken(c)}"

    /** 안드로이드 가족을 초대하는 코드 (서버 주소 + 쓰기 키 + 보기 토큰). */
    fun inviteCode(c: Context): String =
        CODE_PREFIX + Base64.encodeToString("${server(c)}\n${writeKey(c)}\n${viewToken(c)}".toByteArray(), Base64.NO_WRAP)

    // ---------------------------------------------------------------- 시작 · 참여 · 끄기 (네트워크: 화면 스레드 밖에서)

    /** 가족 방을 새로 만들고 지금까지의 내역을 모두 올린다. */
    fun start(c: Context, owner: String) {
        val res = JSONObject(request("POST", "${server(c)}/api/households", null, "{}"))
        if (!res.optBoolean("ok")) error(res.optString("error", "응답 오류"))
        prefs(c).edit()
            .putString("write", res.getString("writeKey"))
            .putString("view", res.getString("viewToken"))
            .putString("owner", owner.trim())
            .putString("pending", "{}")
            .apply()
    }

    /** 받은 초대 코드나 보기 링크로 참여한다. 초대 코드면 내 내역도 올릴 수 있고, 보기 링크면 보기만 한다. */
    fun join(c: Context, codeOrLink: String, owner: String): Boolean {
        val s = codeOrLink.trim()
        if (s.startsWith(CODE_PREFIX)) {
            val text = try { String(Base64.decode(s.removePrefix(CODE_PREFIX), Base64.DEFAULT)) } catch (e: IllegalArgumentException) { return false }
            val p = text.split("\n")
            if (p.size != 3 || !p[0].startsWith("https://")) return false
            prefs(c).edit().putString("server", p[0]).putString("write", p[1]).putString("view", p[2])
                .putString("owner", owner.trim()).putString("pending", "{}").apply()
            return true
        }
        val m = Regex("""(https://[^\s/]+)/v/([A-Za-z0-9_-]{10,64})""").find(s) ?: return false
        prefs(c).edit().putString("server", m.groupValues[1]).putString("view", m.groupValues[2]).remove("write")
            .putString("owner", owner.trim()).putString("pending", "{}").apply()
        return true
    }

    /** 공유를 끈다. 이 폰이 쓰기 키를 가졌으면 서버의 가족 방과 내역도 모두 지운다. */
    fun stop(c: Context, deleteOnServer: Boolean) {
        if (deleteOnServer && canWrite(c)) {
            val res = JSONObject(request("DELETE", "${server(c)}/api/households", writeKey(c), null))
            if (!res.optBoolean("ok")) error(res.optString("error", "응답 오류"))
        }
        prefs(c).edit().remove("write").remove("view").putString("pending", "{}").apply()
    }

    // ---------------------------------------------------------------- 올리기

    fun onSaved(c: Context, e: Entry) = enqueue(c, e, deleted = false)
    fun onDeleted(c: Context, e: Entry) = enqueue(c, e, deleted = true)

    /** 지금까지의 내 내역을 모두 올린다 (처음 공유를 켰을 때, 다시 올리기). */
    fun uploadAll(c: Context, entries: List<Entry>, done: ((Boolean) -> Unit)? = null) {
        entries.forEach { enqueue(c, it, deleted = false, flushNow = false) }
        flush(c, done)
    }

    private fun enqueue(c: Context, e: Entry, deleted: Boolean, flushNow: Boolean = true) {
        if (!canWrite(c) || !uploadOn(c) || e.uid.isEmpty()) return
        val item = JSONObject()
            .put("uid", e.uid)
            .put("owner", owner(c).ifEmpty { "나" })
            .put("kind", e.kind.name)
            .put("amount", e.amount)
            .put("memo", e.memo)
            .put("card", e.card)
            .put("time", e.time.toMillis())
            .put("deleted", deleted)
        synchronized(this) {
            val pending = JSONObject(prefs(c).getString("pending", "{}"))
            pending.put(e.uid, item)
            prefs(c).edit().putString("pending", pending.toString()).apply()
        }
        if (flushNow) flush(c)
    }

    fun pendingCount(c: Context) = JSONObject(prefs(c).getString("pending", "{}")).length()

    /** 쌓인 내역을 200건씩 올린다. 실패하면 남겨 두고 다음 기회에 다시 보낸다. */
    fun flush(c: Context, done: ((Boolean) -> Unit)? = null) {
        worker.execute {
            var ok = true
            try {
                while (canWrite(c)) {
                    val batch = synchronized(this) {
                        val pending = JSONObject(prefs(c).getString("pending", "{}"))
                        pending.keys().asSequence().take(200).map { pending.getJSONObject(it) }.toList()
                    }
                    if (batch.isEmpty()) break
                    val body = JSONObject().put("entries", JSONArray(batch))
                    val res = JSONObject(request("POST", "${server(c)}/api/entries", writeKey(c), body.toString()))
                    if (!res.optBoolean("ok")) error(res.optString("error", "응답 오류"))
                    synchronized(this) {
                        val pending = JSONObject(prefs(c).getString("pending", "{}"))
                        batch.forEach { pending.remove(it.getString("uid")) }
                        prefs(c).edit().putString("pending", pending.toString()).apply()
                    }
                }
            } catch (e: Throwable) {
                ok = false
                Log.w(TAG, "가족 공유 올리기 실패", e)
            }
            done?.invoke(ok)
        }
    }

    // ---------------------------------------------------------------- 받기

    /** 그 달의 가족 전체 내역. 네트워크를 쓰므로 화면 스레드 밖에서 부른다. */
    fun fetchMonth(c: Context, ym: YearMonth): List<Shared> {
        val from = ym.atDay(1).atStartOfDay().toMillis()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay().toMillis()
        val res = JSONObject(request("GET", "${server(c)}/api/entries?v=${viewToken(c)}&from=$from&to=$to", null, null))
        if (!res.optBoolean("ok")) error(res.optString("error", "응답 오류"))
        val arr = res.getJSONArray("entries")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Shared(
                uid = o.optString("uid"),
                owner = o.optString("owner").ifEmpty { "가족" },
                kind = if (o.optString("kind") == "INCOME") Kind.INCOME else Kind.EXPENSE,
                amount = o.optLong("amount"),
                memo = o.optString("memo"),
                card = o.optString("card"),
                time = o.optLong("time"),
            )
        }.sortedByDescending { it.time }
    }

    // ---------------------------------------------------------------- HTTP

    @Suppress("DEPRECATION")
    private fun request(method: String, url: String, bearer: String?, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && !text.startsWith("{")) error("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
