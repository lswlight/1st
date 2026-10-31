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

    /** 아이폰·PC 에서 사파리로 여는 가족 가계부 웹 화면 주소. */
    fun webLink(c: Context): String = "${url(c)}?view=1&key=${key(c)}"

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

function rows_(from, to) {
  const data = sheet_().getDataRange().getValues();
  const entries = [];
  for (let i = 1; i < data.length; i++) {
    const r = data[i];
    if (Number(r[8]) === 1) continue;
    const t = Number(r[6]);
    if (t >= from && t < to) entries.push({ uid: r[0], owner: String(r[1]), kind: r[2], amount: Number(r[3]), memo: String(r[4]), card: String(r[5]), time: t });
  }
  return entries;
}

function doGet(e) {
  const p = e.parameter;
  if (p.key !== SECRET) {
    return p.view ? HtmlService.createHtmlOutput('<p style="font-size:18px;padding:20px">링크가 올바르지 않아요. 공유 링크를 다시 받아 주세요.</p>')
                  : out_({ ok: false, error: '비밀번호가 맞지 않아요' });
  }
  if (p.view) return page_(p);
  return out_({ ok: true, entries: rows_(Number(p.from || 0), Number(p.to || 9e15)) });
}

// ---------------------------------------------------------------- 아이폰·PC 용 웹 화면 (?view=1&key=...)

function won_(n) { return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '원'; }
function esc_(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

function page_(p) {
  const tz = 'Asia/Seoul';
  const ym = /^\d{4}-\d{2}$/.test(p.m || '') ? p.m : Utilities.formatDate(new Date(), tz, 'yyyy-MM');
  const y = Number(ym.slice(0, 4)), m = Number(ym.slice(5, 7));
  const from = Date.UTC(y, m - 1, 1) - 9 * 3600e3, to = Date.UTC(y, m, 1) - 9 * 3600e3;
  const list = rows_(from, to).sort(function (a, b) { return b.time - a.time; });
  const pad = function (n) { return (n < 10 ? '0' : '') + n; };
  const prev = m === 1 ? (y - 1) + '-12' : y + '-' + pad(m - 1);
  const next = m === 12 ? (y + 1) + '-01' : y + '-' + pad(m + 1);
  const base = ScriptApp.getService().getUrl() + '?view=1&key=' + encodeURIComponent(SECRET) + '&m=';
  const isBenefit = function (x) { return x.kind === 'INCOME' && x.memo.indexOf('카드 혜택') === 0; };

  // 사람별 합계
  const owners = {};
  list.forEach(function (x) {
    const o = owners[x.owner] || (owners[x.owner] = { inc: 0, exp: 0 });
    if (x.kind === 'INCOME') o.inc += x.amount; else o.exp += x.amount;
  });
  let totalInc = 0, totalExp = 0, ownerHtml = '';
  Object.keys(owners).forEach(function (k) {
    totalInc += owners[k].inc; totalExp += owners[k].exp;
    ownerHtml += '<div class="row"><b>' + esc_(k) + '</b><span><i class="in">수입 ' + won_(owners[k].inc) + '</i> · <i class="out">지출 ' + won_(owners[k].exp) + '</i></span></div>';
  });

  // 카드별 실제 지출 (결제 - 취소 - 혜택)
  const cards = {};
  list.forEach(function (x) {
    if (x.kind !== 'EXPENSE' && !isBenefit(x)) return;
    const name = x.card || '카드 정보 없음';
    cards[name] = (cards[name] || 0) + (x.kind === 'EXPENSE' ? x.amount : -x.amount);
  });
  const cardHtml = Object.keys(cards).sort(function (a, b) { return cards[b] - cards[a]; }).map(function (k) {
    return '<div class="row"><span>💳 ' + esc_(k) + '</span><b class="out">' + won_(cards[k]) + '</b></div>';
  }).join('') || '<p class="sub">카드 사용 내역이 없어요.</p>';

  // 날짜별 내역
  const days = ['일', '월', '화', '수', '목', '금', '토'];
  let listHtml = '', lastDay = '';
  list.forEach(function (x) {
    const d = new Date(x.time);
    const day = Utilities.formatDate(d, tz, 'M월 d일') + ' (' + days[Number(Utilities.formatDate(d, tz, 'u')) % 7] + ')';
    if (day !== lastDay) { listHtml += '<h3>' + day + '</h3>'; lastDay = day; }
    const sign = x.kind === 'INCOME' ? '+' : (x.amount < 0 ? '취소 ' : '-');
    const cls = x.kind === 'INCOME' ? 'in' : (x.amount < 0 ? 'sub' : 'out');
    listHtml += '<div class="item"><div><div>' + esc_(x.memo) + '</div><div class="sub">' +
      [Utilities.formatDate(d, tz, 'HH:mm'), esc_(x.owner), esc_(x.card)].filter(String).join(' · ') +
      '</div></div><b class="' + cls + '">' + sign + won_(Math.abs(x.amount)) + '</b></div>';
  });
  if (!listHtml) listHtml = '<p class="sub">이번 달 내역이 없어요.</p>';

  const html = '<!doctype html><html lang="ko"><head><meta charset="utf-8">' +
    '<style>body{font-family:-apple-system,system-ui,sans-serif;margin:0;background:#fff;color:#14213d}' +
    '.wrap{max-width:560px;margin:0 auto;padding:16px}' +
    '.nav{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}' +
    '.nav a{background:#e9ecf2;color:#14213d;text-decoration:none;padding:10px 16px;border-radius:10px;font-weight:700}' +
    '.nav b{font-size:20px}.card{background:#f2f4f7;border-radius:14px;padding:12px 14px;margin-bottom:12px}' +
    '.row{display:flex;justify-content:space-between;gap:8px;padding:5px 0;font-size:15px}.row i{font-style:normal}' +
    '.total{border-top:1px solid #d9dee7;margin-top:6px;padding-top:8px;font-size:17px}' +
    'h2{font-size:17px;margin:18px 0 8px}h3{font-size:13px;color:#6b7280;margin:16px 0 4px}' +
    '.item{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #f1f3f6;font-size:15px}' +
    '.sub{color:#6b7280;font-size:12px}.in{color:#1e6fd9}.out{color:#d93025}' +
    '.refresh{display:block;text-align:center;background:#1e6fd9;color:#fff;text-decoration:none;padding:12px;border-radius:12px;font-weight:700;margin:4px 0 14px}</style>' +
    '</head><body><div class="wrap">' +
    '<div class="nav"><a target="_top" href="' + base + prev + '">◀</a><b>' + y + '년 ' + m + '월</b><a target="_top" href="' + base + next + '">▶</a></div>' +
    '<a class="refresh" target="_top" href="' + base + ym + '">새로고침</a>' +
    '<div class="card">' + (ownerHtml || '<p class="sub">아직 올라온 내역이 없어요.</p>') +
    '<div class="row total"><b>가족 합계</b><b>남은 돈 ' + won_(totalInc - totalExp) + '</b></div></div>' +
    '<h2>카드별 실제 지출</h2><div class="card">' + cardHtml + '</div>' +
    '<h2>내역</h2>' + listHtml +
    '<p class="sub" style="margin-top:20px">' + Utilities.formatDate(new Date(), tz, 'M/d HH:mm') + ' 기준 · 자동가계부</p>' +
    '</div></body></html>';
  return HtmlService.createHtmlOutput(html).setTitle('가족 가계부').addMetaTag('viewport', 'width=device-width, initial-scale=1');
}
""".trimIndent()
}
