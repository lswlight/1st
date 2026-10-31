package com.lswlight.moneybook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 가족 가계부: 구글 시트에 모인 가족 모두의 내역을 사람별 합계와 함께 보여 준다. */
class FamilyActivity : Activity() {

    private var month: YearMonth = YearMonth.now()
    private lateinit var monthLabel: TextView
    private lateinit var status: TextView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(StatsActivity.EXTRA_MONTH)?.let { month = YearMonth.parse(it) }
        title = "가족 가계부"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(buildLayout())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildLayout(): View {
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        root.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("◀") { month = month.minusMonths(1); load() })
            monthLabel = text("", 20f, bold = true).apply { gravity = Gravity.CENTER }
            addView(monthLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("▶") { month = month.plusMonths(1); load() })
        })
        root.addView(horizontal().apply {
            addView(button("새로고침") { load() }, weight())
            addView(button("공유 설정") { showSettings() }, weight())
        }, margins(top = 4))
        status = text("", 12f, Color.GRAY).apply { setPadding(dp(4), dp(6), 0, 0) }
        root.addView(status)
        content = vertical()
        root.addView(content, margins(top = 8))
        root.addView(button("← 메인 화면으로 돌아가기") { finish() }, margins(top = 20))
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
    }

    private fun load() {
        monthLabel.text = "${month.year}년 ${month.monthValue}월"
        content.removeAllViews()
        if (!FamilySync.configured(this)) {
            status.text = ""
            showIntro()
            return
        }
        status.text = "불러오는 중..."
        val ym = month
        Thread {
            val result = runCatching { FamilySync.fetchMonth(this, ym) }
            runOnUiThread {
                if (ym != month || isFinishing) return@runOnUiThread
                result.onSuccess { show(it) }.onFailure {
                    status.text = "불러오지 못했어요: ${it.message ?: it.javaClass.simpleName}\n인터넷 연결과 공유 설정을 확인해 주세요."
                }
            }
        }.start()
    }

    private fun show(list: List<FamilySync.Shared>) {
        val pending = FamilySync.pendingCount(this)
        status.text = "방금 불러옴 · 가족 ${list.map { it.owner }.distinct().size}명 · ${list.size}건" +
            if (pending > 0) " · 아직 못 올린 내 내역 ${pending}건" else ""

        // 사람별 합계
        val byOwner = list.groupBy { it.owner }
        content.addView(vertical().apply {
            background = rounded(Color.parseColor("#F2F4F7"), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            for ((owner, items) in byOwner) {
                val inc = items.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
                val exp = items.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
                addView(text(owner, 15f, bold = true).apply { setPadding(0, dp(4), 0, 0) })
                addView(horizontal().apply {
                    addView(text("수입 ${MainActivity.won(inc)}", 13f, StatsActivity.INCOME_COLOR), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text("지출 ${MainActivity.won(exp)}", 13f, StatsActivity.EXPENSE_COLOR))
                })
            }
            val inc = list.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
            val exp = list.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
            addView(View(this@FamilyActivity).apply { setBackgroundColor(Color.parseColor("#D9DEE7")) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(6) })
            addView(horizontal().apply {
                addView(text("가족 합계", 15f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text("남은 돈 ${MainActivity.won(inc - exp)}", 15f, bold = true))
            })
        })

        if (list.isEmpty()) {
            content.addView(text("이번 달 가족 내역이 없어요.", 14f, Color.GRAY).apply { setPadding(0, dp(16), 0, 0) })
            return
        }
        var lastDay = -1
        for (e in list) {
            val t = e.time.toLocalDateTime()
            if (t.dayOfMonth != lastDay) {
                lastDay = t.dayOfMonth
                content.addView(text(t.format(DAY_HEADER), 13f, Color.GRAY, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) })
            }
            content.addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(vertical().apply {
                    addView(text(e.memo, 15f).apply { maxLines = 1 })
                    addView(text(listOf(t.format(TIME), e.owner, e.card).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Color.GRAY))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val (sign, color) = when {
                    e.kind == Kind.INCOME -> "+" to StatsActivity.INCOME_COLOR
                    e.amount < 0 -> "취소 " to Color.GRAY
                    else -> "-" to StatsActivity.EXPENSE_COLOR
                }
                addView(text(sign + MainActivity.won(Math.abs(e.amount)), 15f, color, bold = true))
            })
        }
    }

    private fun showIntro() {
        content.addView(vertical().apply {
            background = rounded(Color.parseColor("#E8F1FD"), 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("가족 가계부를 시작해 볼까요?", 16f, bold = true))
            addView(text(
                "가족 모두의 내역을 한 구글 시트에 모아 각자 폰에서 함께 봐요. 서버나 가입은 필요 없어요.\n\n" +
                    "■ 처음 만드는 사람 (예: 남편)\n" +
                    "1) [공유 설정] → [스크립트 복사]\n" +
                    "2) 구글 시트를 새로 만들고 확장 프로그램 → Apps Script 에 붙여 넣기\n" +
                    "3) 배포 → 새 배포 → 유형 '웹 앱', 액세스 '모든 사용자' → 배포 → 웹 앱 URL 복사\n" +
                    "4) [공유 설정]의 '시트 주소'에 붙여 넣고 저장 → [지금까지 내역 올리기]\n" +
                    "5) [공유 코드 복사] → 카카오톡으로 가족에게 보내기\n\n" +
                    "■ 함께 보는 사람 (예: 아내)\n" +
                    "[공유 설정] → 받은 공유 코드 붙여 넣기 → 내 이름 적고 저장",
                14f, Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, 0) })
            addView(button("공유 설정 열기") { showSettings() }, margins(top = 8))
        })
    }

    private fun showSettings() {
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val owner = field(form, "내 이름 (목록에 표시돼요, 예: 남편)", FamilySync.owner(this))
        val code = field(form, "받은 공유 코드 (가족이 보내 준 MBSHARE:… 를 붙여 넣기)", "")
        val url = field(form, "시트 주소 (Apps Script 웹 앱 URL)", FamilySync.url(this))
        val key = field(form, "공유 비밀번호 (자동 생성, 가족 모두 같아야 해요)", FamilySync.key(this))
        val upload = CheckBox(this).apply {
            text = "내 내역도 가족 가계부에 올리기"
            isChecked = FamilySync.uploadOn(this@FamilyActivity)
        }
        form.addView(upload, margins(top = 8))
        form.addView(button("스크립트 복사 (처음 만드는 사람)") {
            copy("자동가계부 스크립트", FamilySync.script(this))
            toast("복사했어요. 구글 시트 Apps Script 에 붙여 넣으세요")
        }, margins(top = 8))
        form.addView(button("공유 코드 복사 (가족에게 보내기)") {
            if (!FamilySync.configured(this)) toast("먼저 시트 주소를 넣고 저장해 주세요")
            else { copy("자동가계부 공유 코드", FamilySync.shareCode(this)); toast("복사했어요. 카카오톡으로 가족에게 보내세요") }
        })
        form.addView(button("지금까지 내역 모두 올리기") {
            if (!FamilySync.configured(this)) { toast("먼저 시트 주소를 넣고 저장해 주세요"); return@button }
            toast("올리는 중...")
            FamilySync.uploadAll(this, LedgerDb.get(this).all())
            FamilySync.flush(this) { ok -> runOnUiThread { toast(if (ok) "모두 올렸어요" else "올리지 못했어요. 주소와 인터넷을 확인해 주세요"); load() } }
        })

        AlertDialog.Builder(this)
            .setTitle("가족 공유 설정")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("저장") { _, _ ->
                var u = url.text.toString()
                var k = key.text.toString()
                if (code.text.isNotBlank()) {
                    val parsed = FamilySync.parseCode(code.text.toString())
                    if (parsed == null) { toast("공유 코드가 올바르지 않아요"); return@setPositiveButton }
                    u = parsed.first
                    k = parsed.second
                }
                FamilySync.save(this, u, k, owner.text.toString(), upload.isChecked)
                toast("저장했어요")
                load()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ---------------------------------------------------------------- 도우미

    private fun copy(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(text(label, 12f, Color.GRAY).apply { setPadding(0, dp(10), 0, 0) })
        return EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            parent.addView(this)
        }
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun weight() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    private fun margins(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_HEADER = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    }
}
