package com.lswlight.moneybook

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 한 화면짜리 가계부: 월 이동, 수입/지출/잔액 요약, 내역 목록. 레이아웃은 코드로 만든다. */
class MainActivity : Activity() {

    private lateinit var db: LedgerDb
    private var month: YearMonth = YearMonth.now()

    private lateinit var monthLabel: TextView
    private lateinit var incomeText: TextView
    private lateinit var expenseText: TextView
    private lateinit var balanceText: TextView
    private lateinit var permissionBanner: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LedgerDb.get(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        LedgerDb.onChanged = { refresh() }
        if (FamilySync.configured(this) && FamilySync.pendingCount(this) > 0) FamilySync.flush(this)
        // 알림 접근은 켜져 있는데 감지 서비스가 연결돼 있지 않으면 (앱 업데이트 직후 등) 다시 연결을 요청한다
        if (listenerEnabled() && !CardNotificationListener.connected) {
            NotificationListenerService.requestRebind(ComponentName(this, CardNotificationListener::class.java))
            monthLabel.postDelayed({ refresh() }, 2000)
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        LedgerDb.onChanged = null
    }

    // ---------------------------------------------------------------- 화면 구성

    private fun buildLayout(): View {
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }

        // 월 이동
        root.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("◀") { month = month.minusMonths(1); refresh() })
            monthLabel = text("", 20f, bold = true).apply { gravity = Gravity.CENTER }
            addView(monthLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("▶") { month = month.plusMonths(1); refresh() })
        })

        // 요약 카드
        root.addView(vertical().apply {
            background = rounded(Color.parseColor("#F2F4F7"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            incomeText = summaryRow(this, "수입", INCOME_COLOR)
            expenseText = summaryRow(this, "지출", EXPENSE_COLOR)
            balanceText = summaryRow(this, "남은 돈", Color.BLACK, big = true)
        }, margins(top = 8))

        statusText = text("", 12f, Color.GRAY).apply { setPadding(dp(4), dp(6), 0, 0) }
        root.addView(statusText)

        // 알림 접근 권한 안내
        permissionBanner = vertical().apply {
            background = rounded(Color.parseColor("#FFF4E5"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(text("카드 결제 자동 기록이 꺼져 있어요.\n'알림 접근'에서 이 앱을 허용해 주세요.", 14f))
            addView(button("알림 접근 설정 열기") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
        root.addView(permissionBanner, margins(top = 8))

        // 달력·그래프 화면
        root.addView(horizontal().apply {
            addView(button("📅 달력 · 📊 그래프") { openStats(StatsActivity.TAB_CALENDAR) }, weight())
            addView(button("💳 카드별 지출") { openStats(StatsActivity.TAB_CARDS) }, weight())
        }, margins(top = 8))
        root.addView(button("👨‍👩‍👧 가족 가계부 (함께 보기)") {
            startActivity(Intent(this, FamilyActivity::class.java).putExtra(StatsActivity.EXTRA_MONTH, month.toString()))
        }, margins(top = 0))

        // 입력 버튼
        root.addView(horizontal().apply {
            addView(button("+ 수입") { editEntry(null, Kind.INCOME) }, weight())
            addView(button("+ 지출") { editEntry(null, Kind.EXPENSE) }, weight())
            addView(button("문자 붙여넣기") { pasteMessage() }, weight())
        }, margins(top = 8))

        root.addView(horizontal().apply {
            addView(button("최근 감지 알림") { showDetectLog() }, weight())
            addView(button("환율·수수료") { showRates() }, weight())
            addView(button("기록 제외") { showExcludeList() }, weight())
        })

        list = vertical()
        root.addView(list, margins(top = 8))

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
    }

    private fun refresh() {
        monthLabel.text = "${month.year}년 ${month.monthValue}월"
        val enabled = listenerEnabled()
        permissionBanner.visibility = if (enabled) View.GONE else View.VISIBLE
        statusText.text = when {
            !enabled -> "자동 감지: 꺼짐"
            CardNotificationListener.connected -> "자동 감지: 동작 중 ●"
            else -> "자동 감지: 연결 안 됨 — 계속 이 상태면 알림 접근을 껐다가 다시 켜 주세요"
        }
        statusText.setTextColor(if (enabled && CardNotificationListener.connected) INCOME_COLOR else EXPENSE_COLOR)

        val entries = db.month(month)
        val income = entries.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
        val expense = entries.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
        incomeText.text = won(income)
        expenseText.text = won(expense)
        balanceText.text = won(income - expense)

        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(text("이번 달 내역이 없어요.", 14f, Color.GRAY).apply {
                setPadding(0, dp(24), 0, 0)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        var lastDay = -1
        for (e in entries) {
            if (e.time.dayOfMonth != lastDay) {
                lastDay = e.time.dayOfMonth
                list.addView(text(e.time.format(DAY_HEADER), 13f, Color.GRAY, bold = true).apply {
                    setPadding(0, dp(14), 0, dp(4))
                })
            }
            list.addView(entryRow(e))
        }
    }

    private fun entryRow(e: Entry): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { editEntry(e, e.kind) }

        val left = vertical()
        left.addView(text(e.memo, 16f))
        val sub = listOf(e.time.format(TIME), e.card, if (e.auto) "자동" else "").filter { it.isNotEmpty() }
        left.addView(text(sub.joinToString(" · "), 12f, Color.GRAY))
        addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val (sign, color) = when {
            e.kind == Kind.INCOME -> "+" to INCOME_COLOR
            e.amount < 0 -> "취소 " to Color.GRAY
            else -> "-" to EXPENSE_COLOR
        }
        addView(text(sign + won(Math.abs(e.amount)), 16f, color, bold = true))
    }

    // ---------------------------------------------------------------- 입력/수정

    private fun editEntry(existing: Entry?, kind: Kind, prefill: Entry? = null) {
        val src = existing ?: prefill
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }

        val amount = field(form, "금액 (원)", src?.amount?.toString() ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        val memo = field(form, if (kind == Kind.INCOME) "내용" else "사용처",
            src?.memo ?: if (kind == Kind.INCOME) "월급" else "")
        val card = if (kind == Kind.EXPENSE) field(form, "카드", src?.card ?: "") else null
        val time = field(form, "날짜 (예: 2026-10-27 12:30)",
            (src?.time ?: defaultTime()).format(INPUT_TIME))
        if (src != null && src.raw.isNotEmpty()) {
            form.addView(text("원본 알림\n${src.raw}", 12f, Color.GRAY).apply { setPadding(0, dp(12), 0, 0) })
        }
        // 잘못 분류된 내역(예: 할인 혜택이 지출로 기록됨)을 바로잡는 버튼
        var dialogRef: AlertDialog? = null
        if (existing != null) {
            val other = if (kind == Kind.INCOME) Kind.EXPENSE else Kind.INCOME
            form.addView(button(if (other == Kind.INCOME) "수입으로 바꾸기" else "지출로 바꾸기") {
                db.save(existing.copy(kind = other, amount = Math.abs(existing.amount)))
                refresh()
                dialogRef?.dismiss()
            }, margins(top = 12))
        }
        // 계좌 이체처럼 지출이 아닌 자동 기록: 이 상대를 제외 목록에 넣고 이 내역도 지운다
        if (existing != null && existing.auto && existing.kind == Kind.EXPENSE) {
            form.addView(button("이 상대는 앞으로 기록 안 함") {
                ExcludeList.add(this, existing.memo)
                db.delete(existing.id)
                refresh()
                toast("'${existing.memo}' 을(를) 제외 목록에 넣었어요")
                dialogRef?.dismiss()
            })
        }

        val title = (if (existing == null) "추가: " else "수정: ") + if (kind == Kind.INCOME) "수입" else "지출"
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("저장", null)
            .setNegativeButton("닫기", null)
        if (existing != null) {
            builder.setNeutralButton("삭제") { _, _ -> db.delete(existing.id); refresh() }
        }
        val dialog = builder.create()
        dialogRef = dialog
        dialog.setOnShowListener {
            // 입력이 잘못되면 창을 닫지 않고 알려 준다
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = amount.text.toString().replace(",", "").trim().toLongOrNull()
                val at = try {
                    LocalDateTime.parse(time.text.toString().trim(), INPUT_TIME)
                } catch (e: DateTimeParseException) {
                    null
                }
                when {
                    value == null || value == 0L -> toast("금액을 숫자로 입력해 주세요")
                    at == null -> toast("날짜를 2026-10-27 12:30 형식으로 입력해 주세요")
                    else -> {
                        db.save(
                            Entry(
                                id = existing?.id ?: 0,
                                kind = kind,
                                amount = value,
                                memo = memo.text.toString().trim().ifEmpty { if (kind == Kind.INCOME) "수입" else "지출" },
                                card = card?.text?.toString()?.trim().orEmpty(),
                                time = at,
                                auto = existing?.auto ?: false,
                                raw = src?.raw.orEmpty(),
                                uid = existing?.uid.orEmpty(),
                            )
                        )
                        month = YearMonth.from(at)
                        refresh()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    /** 자동 기록이 안 된 문자를 붙여 넣어 지출로 추가한다. 내 카드 문자가 잘 읽히는지 시험할 때도 쓴다. */
    private fun pasteMessage() {
        val input = EditText(this).apply {
            hint = "카드 승인 문자나 알림 내용을 붙여 넣으세요"
            minLines = 4
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("문자 붙여넣기")
            .setView(vertical().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("읽기") { _, _ ->
                val raw = input.text.toString()
                val p = CardMessageParser.parse(
                    raw, LocalDateTime.now(), trustedSource = true,
                    rates = ExchangeRates.cached(this), feePercent = ExchangeRates.feePercent(this),
                )
                if (p == null) {
                    toast("카드 결제 문구를 찾지 못했어요")
                } else {
                    val kind = if (p.isBenefit) Kind.INCOME else Kind.EXPENSE
                    editEntry(
                        null, kind,
                        Entry(
                            kind = kind,
                            amount = if (p.isCancel) -p.amount else p.amount,
                            memo = p.memo(),
                            card = p.card,
                            time = p.time ?: LocalDateTime.now(),
                            raw = raw,
                        ),
                    )
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 자동 기록이 안 될 때 원인을 보는 창: 앱이 받은 알림 원문과 처리 결과. */
    private fun showDetectLog() {
        val items = DetectLog.list(this)
        val body = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        if (items.isEmpty()) {
            body.addView(text("아직 받은 알림이 없어요.\n알림 접근이 허용되어 있는지 확인해 주세요.", 14f, Color.GRAY))
        }
        for (it in items) {
            body.addView(text("${it.time.format(LOG_TIME)} · ${it.app}", 12f, Color.GRAY).apply {
                setPadding(0, dp(12), 0, 0)
            })
            val color = if (it.result.startsWith("기록됨")) INCOME_COLOR else EXPENSE_COLOR
            body.addView(text(it.result, 14f, color, bold = true))
            body.addView(text(it.text.ifBlank { "(알림 내용이 비어 있음)" }, 13f))
        }
        AlertDialog.Builder(this)
            .setTitle("최근 감지 알림")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("닫기", null)
            .setNeutralButton("지우기") { _, _ -> DetectLog.clear(this) }
            .show()
    }

    private fun openStats(tab: Int) {
        startActivity(Intent(this, StatsActivity::class.java)
            .putExtra(StatsActivity.EXTRA_MONTH, month.toString())
            .putExtra(StatsActivity.EXTRA_TAB, tab))
    }

    /** 자동 기록에서 뺄 상대 이름·단어 목록 편집. */
    private fun showExcludeList() {
        val input = EditText(this).apply {
            setText(ExcludeList.terms(this@MainActivity).joinToString("\n"))
            hint = "예)\n이우진\n토스\n카카오뱅크"
            minLines = 5
            gravity = Gravity.TOP
        }
        val body = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text("한 줄에 하나씩 적으면, 사용처(상대)에 그 단어가 들어간 알림은 기록하지 않아요.\n" +
                "내 이름·가족 이름·내 다른 계좌 은행처럼 이체 상대를 넣어 주세요.\n" +
                "이체·송금·ATM 출금과 입금(월급 등)은 원래 기록하지 않아요.", 13f, Color.GRAY))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("기록 제외 목록")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("저장") { _, _ ->
                ExcludeList.save(this, input.text.toString().lines())
                toast("저장했어요")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 받아 둔 환율 확인, 지금 받기, 해외 이용 수수료율 설정. */
    private fun showRates() {
        val body = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val info = text("", 14f)
        fun fillInfo() {
            val rates = ExchangeRates.cached(this)
            val date = ExchangeRates.updatedOn(this)
            info.text = buildString {
                append(if (date == null) "아직 받은 환율이 없어 고정 추정치를 씁니다.\n" else "${date} 기준 환율\n")
                for (code in listOf("JPY", "USD", "EUR", "CNY")) {
                    val v = rates[code] ?: CardMessageParser.FALLBACK_KRW_PER_UNIT[code] ?: continue
                    append("1 $code = ${"%,.2f".format(v)}원${if (code in rates) "" else " (고정)"}\n")
                }
                append("해외 결제가 있는 날 하루 한 번 자동으로 받습니다.")
            }
        }
        fillInfo()
        body.addView(info)
        val fee = field(body, "해외 이용 수수료율 (%) — 환산 금액에 더함, 예: 1.4",
            ExchangeRates.feePercent(this).let { if (it == 0.0) "0" else it.toString() },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        val dialog = AlertDialog.Builder(this)
            .setTitle("환율·수수료")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("저장", null)
            .setNeutralButton("지금 환율 받기", null)
            .setNegativeButton("닫기", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = fee.text.toString().trim().toDoubleOrNull()
                if (v == null || v < 0 || v > 10) {
                    toast("수수료율은 0~10 사이 숫자로 입력해 주세요")
                } else {
                    ExchangeRates.setFeePercent(this, v)
                    toast("저장했어요. 다음 해외 결제부터 적용됩니다")
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                info.text = "환율을 받는 중..."
                Thread {
                    val ok = ExchangeRates.refresh(this)
                    runOnUiThread {
                        fillInfo()
                        toast(if (ok) "최신 환율을 받았어요" else "환율을 받지 못했어요. 인터넷 연결을 확인해 주세요")
                    }
                }.start()
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- 도우미

    private fun listenerEnabled(): Boolean {
        val me = ComponentName(this, CardNotificationListener::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it == me }
    }

    // 지난달을 보고 있으면 그 달 1일 정오를 기본값으로
    private fun defaultTime(): LocalDateTime =
        if (YearMonth.now() == month) LocalDateTime.now().withSecond(0).withNano(0)
        else month.atDay(1).atTime(12, 0)

    private fun summaryRow(parent: LinearLayout, label: String, color: Int, big: Boolean = false): TextView {
        val size = if (big) 20f else 16f
        val value = text("", size, color, bold = true).apply { gravity = Gravity.END }
        parent.addView(horizontal().apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(text(label, size), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(value)
        })
        return value
    }

    private fun field(parent: LinearLayout, label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        parent.addView(text(label, 12f, Color.GRAY).apply { setPadding(0, dp(10), 0, 0) })
        return EditText(this).apply {
            setText(value)
            inputType = type
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

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun weight() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun margins(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val INCOME_COLOR = Color.parseColor("#1E6FD9")
        private val EXPENSE_COLOR = Color.parseColor("#D93025")
        private val DAY_HEADER = DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val LOG_TIME = DateTimeFormatter.ofPattern("M/d HH:mm:ss")
        private val INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun won(v: Long) = "%,d원".format(v)
    }
}
