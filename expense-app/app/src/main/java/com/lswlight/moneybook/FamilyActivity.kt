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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
        setContentView(buildLayout())
    }


    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildLayout(): View {
        val root = vertical().apply { setPadding(dp(20), dp(4), dp(20), dp(24)) }
        root.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("‹") { month = month.minusMonths(1); load() })
            monthLabel = text("", 19f, bold = true).apply { gravity = Gravity.CENTER }
            addView(monthLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("›") { month = month.plusMonths(1); load() })
        })
        root.addView(horizontal().apply {
            addView(Ui.pill(this@FamilyActivity, "새로고침", Ui.GREEN, Color.WHITE) { load() }, weight().apply { rightMargin = dp(8) })
            addView(button("공유 설정") { showSettings() }, weight())
        }, margins(top = 10))
        status = text("", 12f, Color.GRAY).apply { setPadding(dp(4), dp(6), 0, 0) }
        root.addView(status)
        content = vertical()
        root.addView(content, margins(top = 8))
        val page = vertical().apply {
            addView(Ui.header(this@FamilyActivity, "가족 가계부"))
            addView(root)
        }
        return Ui.screen(this, ScrollView(this).apply { addView(page) }, Ui.tabBar(this, Ui.TAB_FAMILY, month.toString()))
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
            background = rounded(Ui.CARD, 20)
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
            background = rounded(Ui.CARD, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text("가족과 가계부를 함께 볼까요? 👨‍👩‍👧", 17f, bold = true))
            addView(text(
                "버튼 한 번이면 시작돼요. 가입이나 구글 시트 설정은 필요 없어요.\n\n" +
                    "① [가족 공유 시작하기]를 누르면 보기 링크가 만들어져요\n" +
                    "② 링크를 카카오톡으로 가족에게 보내요\n" +
                    "③ 가족은 링크만 열면 바로 내 가계부를 봐요 (아이폰·PC는 웹으로, 앱 설치 필요 없음)\n\n" +
                    "이후 결제는 자동으로 올라가고, 가족은 열 때마다 최신 내역을 봐요.",
                14f, Ui.SUB,
            ).apply { setPadding(0, dp(8), 0, dp(12)); setLineSpacing(0f, 1.2f) })
            if (!FamilySync.hasServer(this@FamilyActivity)) {
                addView(text("⚠️ 아직 공유 서버 주소가 없어요. [공유 설정] → 서버 주소를 먼저 넣어 주세요.", 13f, Ui.CORAL).apply { setPadding(0, 0, 0, dp(10)) })
            }
            addView(Ui.pill(this@FamilyActivity, "가족 공유 시작하기", Ui.GREEN, Color.WHITE, 15f) { startSharing() })
            addView(Ui.pill(this@FamilyActivity, "받은 링크·초대 코드로 참여하기") { joinDialog() }, margins(top = 8))
        })
    }

    /** 이름을 묻고 가족 방을 만든 뒤 지금까지 내역을 올린다. */
    private fun startSharing() {
        if (!FamilySync.hasServer(this)) { showSettings(); return }
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val owner = field(form, "내 이름 (가족 화면에 표시돼요, 예: 남편)", FamilySync.owner(this).ifEmpty { "남편" })
        AlertDialog.Builder(this)
            .setTitle("가족 공유 시작하기")
            .setView(form)
            .setPositiveButton("시작") { _, _ ->
                toast("만드는 중...")
                Thread {
                    val r = runCatching {
                        FamilySync.start(this, owner.text.toString().ifBlank { "나" })
                        FamilySync.uploadAll(this, LedgerDb.get(this).all())
                    }
                    runOnUiThread {
                        r.onSuccess { load(); shareLinkDialog() }
                            .onFailure { toast("시작하지 못했어요: ${it.message}. 인터넷 연결을 확인해 주세요") }
                    }
                }.start()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 만든 보기 링크를 가족에게 보내는 창. */
    private fun shareLinkDialog() {
        val link = FamilySync.viewLink(this)
        AlertDialog.Builder(this)
            .setTitle("가족에게 링크 보내기")
            .setMessage("이 링크를 카카오톡 등으로 보내면 가족이 바로 볼 수 있어요.\n아이폰은 사파리에서 열고 공유 → '홈 화면에 추가'를 하면 앱처럼 쓸 수 있어요.\n\n$link")
            .setPositiveButton("링크 보내기") { _, _ -> shareText(link) }
            .setNeutralButton("복사") { _, _ -> copy("가족 가계부 링크", link); toast("복사했어요") }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun joinDialog() {
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val code = field(form, "받은 보기 링크 또는 초대 코드(MBSHARE2:…)", "")
        val owner = field(form, "내 이름 (예: 아내)", FamilySync.owner(this))
        AlertDialog.Builder(this)
            .setTitle("가족 공유 참여하기")
            .setView(form)
            .setPositiveButton("참여") { _, _ ->
                if (FamilySync.join(this, code.text.toString(), owner.text.toString().ifBlank { "가족" })) {
                    toast("참여했어요")
                    if (FamilySync.canWrite(this)) FamilySync.uploadAll(this, LedgerDb.get(this).all())
                    load()
                } else toast("링크나 코드가 올바르지 않아요")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showSettings() {
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val configured = FamilySync.configured(this)
        val owner = field(form, "내 이름 (가족 화면에 표시돼요)", FamilySync.owner(this))
        val upload = CheckBox(this).apply {
            text = "내 내역도 가족 가계부에 올리기"
            isChecked = FamilySync.uploadOn(this@FamilyActivity)
            isEnabled = FamilySync.canWrite(this@FamilyActivity)
        }
        form.addView(upload, margins(top = 8))
        if (configured) {
            form.addView(button("보기 링크 보내기 (아이폰·PC 가족)") { shareLinkDialog() }, margins(top = 10))
            if (FamilySync.canWrite(this)) {
                form.addView(button("초대 코드 보내기 (안드로이드 가족도 자기 내역 올리기)") {
                    shareText(FamilySync.inviteCode(this))
                }, margins(top = 8))
                form.addView(button("지금까지 내역 다시 올리기") {
                    toast("올리는 중...")
                    FamilySync.uploadAll(this, LedgerDb.get(this).all()) { ok ->
                        runOnUiThread { toast(if (ok) "모두 올렸어요" else "올리지 못했어요. 인터넷을 확인해 주세요"); load() }
                    }
                }, margins(top = 8))
            }
            form.addView(Ui.pill(this, "가족 공유 끄기", Ui.PEACH, Ui.CORAL) { confirmStop() }, margins(top = 8))
        }
        val server = field(form, "공유 서버 주소 (보통은 바꿀 필요 없어요)", FamilySync.server(this))

        AlertDialog.Builder(this)
            .setTitle("가족 공유 설정")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("저장") { _, _ ->
                FamilySync.setOwner(this, owner.text.toString())
                FamilySync.setUpload(this, upload.isChecked)
                FamilySync.setServer(this, server.text.toString())
                toast("저장했어요")
                load()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun confirmStop() {
        val owner = FamilySync.canWrite(this)
        AlertDialog.Builder(this)
            .setTitle("가족 공유를 끌까요?")
            .setMessage(if (owner) "서버에 올린 가족 가계부 내역이 모두 지워지고, 보낸 링크로도 더 이상 볼 수 없어요. 내 폰의 기록은 그대로 남아요."
                        else "이 폰에서 가족 가계부 보기를 그만둬요. 가족의 기록은 지워지지 않아요.")
            .setPositiveButton("끄기") { _, _ ->
                Thread {
                    val r = runCatching { FamilySync.stop(this, deleteOnServer = owner) }
                    runOnUiThread { r.onSuccess { toast("껐어요"); load() }.onFailure { toast("끄지 못했어요: ${it.message}") } }
                }.start()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun shareText(text: String) {
        startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
            "보내기"))
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

    private fun button(label: String, onClick: () -> Unit) = Ui.pill(this, label, onClick = onClick)

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
