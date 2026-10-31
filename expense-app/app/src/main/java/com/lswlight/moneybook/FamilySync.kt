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
 * 가족 가계부 공유. 별도 서버 없이 사용자 본인의 구글 시트(Apps Script 웹 앱)를 공유 저장소로 쓴다.
 *  - 내 폰: 내역을 저장·수정·삭제할 때마다 시트에 올린다 (실패하면 쌓아 두었다가 다음에 다시 보낸다)
 *  - 가족 폰: 같은 시트에서 그 달 내역을 받아 '가족 가계부' 화면에 보여 준다
 * 시트 주소와 비밀번호를 아는 폰만 읽고 쓸 수 있다.
 */
object FamilySync {
    private const val PREFS = "family_sync"
    private const val TAG = "MoneyBook"
    private const val CODE_PREFIX = "MBSHARE:"

    private val worker = Executors.newSingleThreadExecutor()

    data class Shared(val uid: String, val owner: String, val kind: Kind, val amount: Long, val memo: String, val card: String, val time: Long)

    // ---------------------------------------------------------------- 설정

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(c: Context) = prefs(c).getString("url", "").orEmpty()
    fun owner(c: Context) = prefs(c).getString("owner", "").orEmpty()
    fun uploadOn(c: Context) = prefs(c).getBoolean("upload", true)
    fun configured(c: Context) = url(c).startsWith("https://") && key(c).isNotEmpty()

    /** 공유 비밀번호. 처음 부를 때 무작위로 만든다. */
    fun key(c: Context): String {
        prefs(c).getString("key", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val k = newUid().replace("-", "")
        prefs(c).edit().putString("key", k).apply()
        return k
    }

    fun save(c: Context, url: String, key: String, owner: String, upload: Boolean) {
        prefs(c).edit()
            .putString("url", url.trim())
            .putString("key", key.trim())
            .putString("owner", owner.trim())
            .putBoolean("upload", upload)
            .apply()
    }

    /** 가족에게 보낼 공유 코드 (주소 + 비밀번호를 한 줄로). */
    fun shareCode(c: Context): String =
        CODE_PREFIX + Base64.encodeToString("${url(c)}\n${key(c)}".toByteArray(), Base64.NO_WRAP)

    /** 공유 코드를 풀어 (주소, 비밀번호). 형식이 틀리면 null. */
    fun parseCode(code: String): Pair<String, String>? {
        val body = code.trim().substringAfter(CODE_PREFIX, "").ifEmpty { return null }
        val text = try { String(Base64.decode(body, Base64.DEFAULT)) } catch (e: IllegalArgumentException) { return null }
        val parts = text.split("\n")
        return if (parts.size == 2 && parts[0].startsWith("https://") && parts[1].isNotEmpty()) parts[0] to parts[1] else null
    }

    /** 구글 시트 Apps Script 에 붙여 넣을 코드 (이 폰의 비밀번호가 들어 있다). */
    fun script(c: Context) = APPS_SCRIPT.replace("%KEY%", key(c))

    // ---------------------------------------------------------------- 올리기

    fun onSaved(c: Context, e: Entry) = enqueue(c, e, deleted = false)
    fun onDeleted(c: Context, e: Entry) = enqueue(c, e, deleted = true)

    /** 지금까지의 내 내역을 모두 올린다 (처음 공유를 켰을 때). */
    fun uploadAll(c: Context, entries: List<Entry>) {
        entries.forEach { enqueue(c, it, deleted = false, flushNow = false) }
        flush(c)
    }

    private fun enqueue(c: Context, e: Entry, deleted: Boolean, flushNow: Boolean = true) {
        if (!configured(c) || !uploadOn(c) || e.uid.isEmpty()) return
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

    /** 쌓인 내역을 30건씩 올린다. 실패하면 남겨 두고 다음 기회에 다시 보낸다. */
    fun flush(c: Context, done: ((Boolean) -> Unit)? = null) {
        worker.execute {
            var ok = true
            try {
                while (true) {
                    val batch = synchronized(this) {
                        val pending = JSONObject(prefs(c).getString("pending", "{}"))
                        pending.keys().asSequence().take(30).map { pending.getJSONObject(it) }.toList()
                    }
                    if (batch.isEmpty()) break
                    val body = JSONObject().put("key", key(c)).put("entries", JSONArray(batch))
                    val res = JSONObject(post(url(c), body.toString()))
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

    /** 시트에서 그 달의 가족 전체 내역을 받는다. 네트워크를 쓰므로 화면 스레드 밖에서 부른다. */
    fun fetchMonth(c: Context, ym: YearMonth): List<Shared> {
        val from = ym.atDay(1).atStartOfDay().toMillis()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay().toMillis()
        val res = JSONObject(get("${url(c)}?key=${key(c)}&from=$from&to=$to"))
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

    // Apps Script 웹 앱은 결과를 다른 주소로 넘겨(302) 주므로, POST 뒤에는 그 주소를 GET 으로 읽는다
    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 300..399) return get(conn.getHeaderField("Location") ?: error("이동 주소 없음"))
            if (code != 200) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        var target = url
        repeat(5) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 20000
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = conn.getHeaderField("Location") ?: error("이동 주소 없음")
                    return@repeat
                }
                if (code != 200) error("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        error("이동이 너무 많음")
    }

    /** 구글 시트 > 확장 프로그램 > Apps Script 에 붙여 넣는 코드. %KEY% 는 공유 비밀번호로 바뀐다. */
    private val APPS_SCRIPT = """
// 자동가계부 가족 공유용 스크립트 — 그대로 붙여 넣고 [배포 > 새 배포 > 웹 앱]으로 배포하세요.
const SECRET = '%KEY%';
const COLS = ['uid', 'owner', 'kind', 'amount', 'memo', 'card', 'time', 'updated', 'deleted', '날짜'];

function sheet_() {
  const ss = SpreadsheetApp.getActive();
  let sh = ss.getSheetByName('가계부');
  if (!sh) { sh = ss.insertSheet('가계부'); sh.appendRow(COLS); }
  return sh;
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  const req = JSON.parse(e.postData.contents);
  if (req.key !== SECRET) return out_({ ok: false, error: '비밀번호가 맞지 않아요' });
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = sheet_();
    const data = sh.getDataRange().getValues();
    const row = {};
    for (let i = 1; i < data.length; i++) row[data[i][0]] = i + 1;
    (req.entries || []).forEach(function (en) {
      const values = [en.uid, en.owner, en.kind, en.amount, en.memo, en.card, en.time, Date.now(), en.deleted ? 1 : 0,
        Utilities.formatDate(new Date(en.time), 'Asia/Seoul', 'yyyy-MM-dd HH:mm')];
      if (row[en.uid]) sh.getRange(row[en.uid], 1, 1, values.length).setValues([values]);
      else { sh.appendRow(values); row[en.uid] = sh.getLastRow(); }
    });
    return out_({ ok: true, count: (req.entries || []).length });
  } finally {
    lock.releaseLock();
  }
}

function doGet(e) {
  if (e.parameter.key !== SECRET) return out_({ ok: false, error: '비밀번호가 맞지 않아요' });
  const from = Number(e.parameter.from || 0), to = Number(e.parameter.to || 9e15);
  const data = sheet_().getDataRange().getValues();
  const entries = [];
  for (let i = 1; i < data.length; i++) {
    const r = data[i];
    if (Number(r[8]) === 1) continue;
    const t = Number(r[6]);
    if (t >= from && t < to) entries.push({ uid: r[0], owner: String(r[1]), kind: r[2], amount: Number(r[3]), memo: String(r[4]), card: String(r[5]), time: t });
  }
  return out_({ ok: true, entries: entries });
}
""".trimIndent()
}
