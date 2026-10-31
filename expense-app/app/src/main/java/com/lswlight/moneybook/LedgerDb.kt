package com.lswlight.moneybook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

enum class Kind { INCOME, EXPENSE }

data class Entry(
    val id: Long = 0,
    val kind: Kind,
    val amount: Long,          // 지출 취소는 음수
    val memo: String,          // 가맹점 또는 메모
    val card: String = "",
    val time: LocalDateTime,
    val auto: Boolean = false, // 알림에서 자동으로 기록됐으면 true
    val raw: String = "",      // 자동 기록의 원본 알림 문구
    val uid: String = "",      // 가족 공유용 고유번호 (저장할 때 자동으로 붙는다)
)

/** 수입·지출 내역을 폰 안의 SQLite 파일 하나에 저장한다. */
class LedgerDb(context: Context) : SQLiteOpenHelper(context, "moneybook.db", null, 2) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                amount INTEGER NOT NULL,
                memo TEXT NOT NULL,
                card TEXT NOT NULL DEFAULT '',
                time INTEGER NOT NULL,
                auto INTEGER NOT NULL DEFAULT 0,
                raw TEXT NOT NULL DEFAULT '',
                uid TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX idx_entries_time ON entries(time)")
    }

    // 1 → 2: 가족 공유용 uid 칸 추가, 기존 내역에도 번호를 붙인다
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
            db.rawQuery("SELECT id FROM entries", null).use { c ->
                while (c.moveToNext()) {
                    db.execSQL("UPDATE entries SET uid = ? WHERE id = ?", arrayOf<Any>(newUid(), c.getLong(0)))
                }
            }
        }
    }

    fun save(entry: Entry): Long {
        val e = if (entry.uid.isEmpty()) entry.copy(uid = newUid()) else entry
        val v = ContentValues().apply {
            put("kind", e.kind.name)
            put("amount", e.amount)
            put("memo", e.memo)
            put("card", e.card)
            put("time", e.time.toMillis())
            put("auto", if (e.auto) 1 else 0)
            put("raw", e.raw)
            put("uid", e.uid)
        }
        val id = if (e.id == 0L) {
            writableDatabase.insert("entries", null, v)
        } else {
            writableDatabase.update("entries", v, "id = ?", arrayOf(e.id.toString()))
            e.id
        }
        FamilySync.onSaved(appContext, e)
        return id
    }

    fun delete(id: Long) {
        query("id = ?", arrayOf(id.toString())).firstOrNull()?.let { FamilySync.onDeleted(appContext, it) }
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
    }

    /** 가족 공유에 처음 올릴 때 쓰는 전체 내역. */
    fun all(): List<Entry> = query("1 = 1", emptyArray())

    fun month(ym: YearMonth): List<Entry> {
        val from = ym.atDay(1).atStartOfDay().toMillis()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay().toMillis()
        return query("time >= ? AND time < ?", arrayOf(from.toString(), to.toString()))
    }

    /**
     * 같은 결제가 문자와 카드 앱 알림으로 두 번 오는 경우를 거른다.
     * 같은 금액의 자동 기록이 앞뒤 3분 안에 있으면 중복으로 본다.
     */
    fun hasRecentAuto(kind: Kind, amount: Long, time: LocalDateTime): Boolean {
        val t = time.toMillis()
        val window = 3 * 60 * 1000L
        return query(
            "auto = 1 AND kind = ? AND amount = ? AND time BETWEEN ? AND ?",
            arrayOf(kind.name, amount.toString(), (t - window).toString(), (t + window).toString()),
        ).isNotEmpty()
    }

    /** 앞뒤 [minutes]분 안의 같은 금액 자동 지출 중 은행 출금 알림으로 기록된 것 (내 계좌 간 이체 후보). */
    fun recentAutoWithdrawals(amount: Long, time: LocalDateTime, minutes: Long = 5): List<Entry> {
        val t = time.toMillis()
        val window = minutes * 60 * 1000L
        return query(
            "auto = 1 AND kind = ? AND amount = ? AND time BETWEEN ? AND ? AND raw LIKE ?",
            arrayOf(Kind.EXPENSE.name, amount.toString(), (t - window).toString(), (t + window).toString(), "%출금%"),
        )
    }

    private fun query(where: String, args: Array<String>): List<Entry> {
        val out = mutableListOf<Entry>()
        readableDatabase.query("entries", null, where, args, null, null, "time DESC, id DESC").use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    kind = Kind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
                    amount = c.getLong(c.getColumnIndexOrThrow("amount")),
                    memo = c.getString(c.getColumnIndexOrThrow("memo")),
                    card = c.getString(c.getColumnIndexOrThrow("card")),
                    time = c.getLong(c.getColumnIndexOrThrow("time")).toLocalDateTime(),
                    auto = c.getInt(c.getColumnIndexOrThrow("auto")) == 1,
                    raw = c.getString(c.getColumnIndexOrThrow("raw")),
                    uid = c.getString(c.getColumnIndexOrThrow("uid")),
                )
            }
        }
        return out
    }

    companion object {
        @Volatile private var instance: LedgerDb? = null

        fun get(context: Context): LedgerDb =
            instance ?: synchronized(this) {
                instance ?: LedgerDb(context.applicationContext).also { instance = it }
            }

        /** 알림 서비스가 새 내역을 넣으면 열려 있는 화면을 새로 고친다. */
        @Volatile var onChanged: (() -> Unit)? = null
    }
}

private val ZONE: ZoneId = ZoneId.systemDefault()

fun newUid(): String = java.util.UUID.randomUUID().toString()

fun LocalDateTime.toMillis(): Long = atZone(ZONE).toInstant().toEpochMilli()

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), ZONE)
