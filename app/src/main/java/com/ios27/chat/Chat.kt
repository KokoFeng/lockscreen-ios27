package com.ios27.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.PopupWindow
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun isEmojiOnly(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return false
    var n = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i); i += Character.charCount(cp)
        if (cp == 0x200D || cp == 0xFE0F || cp == 0x20E3) continue
        val emoji = cp >= 0x1F000 || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF
        if (!emoji) return false
        n++
    }
    return n in 1..3
}

/** Gelembung pesan gaya iMessage: biru untuk kamu, abu-abu untuk teman; emoji tunggal tampil besar tanpa gelembung. */
class BubbleView(ctx: Context) : View(ctx) {
    private var text = ""
    private var mine = false
    private var tail = false
    private var sizeSp = 17f
    private var emojiOnly = false
    private var reacts = ""
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun set(t: String, m: Boolean, tl: Boolean, size: Float, r: String = "") {
        text = t; mine = m; tail = tl; sizeSp = size; emojiOnly = isEmojiOnly(t); reacts = r
        requestLayout(); invalidate()
    }

    private fun topX() = if (reacts.isEmpty()) 0 else context.dp(14f)
    private fun padH() = if (emojiOnly) 0 else context.dp(13f)
    private fun padV() = if (emojiOnly) 0 else context.dp(8f)
    private fun tailPad() = if (emojiOnly) 0 else context.dp(6f)

    override fun onMeasure(ws: Int, hs: Int) {
        val avail = MeasureSpec.getSize(ws).coerceAtLeast(context.dp(120f))
        val maxW = (avail * 0.78f).toInt()
        val dens = resources.displayMetrics.scaledDensity
        tp.textSize = (if (emojiOnly) sizeSp * 2.4f else sizeSp) * dens
        tp.typeface = Emo.typeface(context) ?: Typeface.DEFAULT
        tp.color = if (mine) Color.WHITE else Theme.text
        val maxText = (maxW - padH() * 2 - tailPad()).coerceAtLeast(context.dp(40f))
        val want = Math.ceil(Layout.getDesiredWidth(text, tp).toDouble()).toInt().coerceIn(1, maxText)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, want).build()
        layout = l
        var mw = 0f
        for (i in 0 until l.lineCount) mw = maxOf(mw, l.getLineWidth(i))
        setMeasuredDimension(Math.ceil(mw.toDouble()).toInt() + padH() * 2 + tailPad(), l.height + padV() * 2 + topX())
    }

    override fun onDraw(c: Canvas) {
        val l = layout ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val left = if (mine) 0f else tailPad().toFloat()
        val right = if (mine) w - tailPad() else w
        val top = topX().toFloat()
        if (!emojiOnly) {
            bg.color = if (mine) Theme.blue else Theme.bubbleIn
            c.drawRoundRect(RectF(left, top, right, h), context.dpf(18f), context.dpf(18f), bg)
            if (tail) {
                path.reset()
                if (mine) {
                    path.moveTo(right - context.dpf(14f), h - context.dpf(16f)); path.lineTo(right + context.dpf(5f), h); path.lineTo(right - context.dpf(20f), h - context.dpf(4f))
                } else {
                    path.moveTo(left + context.dpf(14f), h - context.dpf(16f)); path.lineTo(left - context.dpf(5f), h); path.lineTo(left + context.dpf(20f), h - context.dpf(4f))
                }
                path.close(); c.drawPath(path, bg)
            }
        }
        c.save(); c.translate(left + padH(), top + padV()); l.draw(c); c.restore()
        if (reacts.isNotEmpty()) {
            bp.textSize = 13f * resources.displayMetrics.scaledDensity
            bp.typeface = Emo.typeface(context) ?: Typeface.DEFAULT
            bp.color = Theme.text
            val bw = bp.measureText(reacts) + context.dpf(14f); val bh = context.dpf(24f)
            val bx = (if (mine) left - context.dpf(8f) else right - bw + context.dpf(8f)).coerceIn(0f, maxOf(0f, w - bw))
            bg.color = Theme.card
            c.drawRoundRect(RectF(bx, 0f, bx + bw, bh), bh / 2f, bh / 2f, bg)
            bg.style = Paint.Style.STROKE; bg.strokeWidth = context.dpf(1f); bg.color = Theme.sep
            c.drawRoundRect(RectF(bx, 0f, bx + bw, bh), bh / 2f, bh / 2f, bg)
            bg.style = Paint.Style.FILL
            c.drawText(reacts, bx + context.dpf(7f), bh / 2f - (bp.ascent() + bp.descent()) / 2f, bp)
        }
    }
}

/** Papan emoji bawaan (memakai font emoji kustom bila dipilih). */
class EmojiPanel(ctx: Context, private val onPick: (String) -> Unit) : LinearLayout(ctx) {
    private val cats: List<Pair<String, List<IntRange>>> = listOf(
        "😀" to listOf(0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F97A),
        "👍" to listOf(0x1F440..0x1F487, 0x270A..0x270D),
        "❤" to listOf(0x1F493..0x1F49F, 0x1F9E1..0x1F9E1, 0x2728..0x2728, 0x1F525..0x1F525, 0x1F4A5..0x1F4AF),
        "🐶" to listOf(0x1F400..0x1F43F, 0x1F980..0x1F9AF, 0x1F330..0x1F344),
        "🍔" to listOf(0x1F345..0x1F37F, 0x1F950..0x1F96F),
        "⚽" to listOf(0x1F380..0x1F3CF),
        "🚗" to listOf(0x1F680..0x1F6C5, 0x1F3E0..0x1F3F0, 0x1F30D..0x1F320),
        "💡" to listOf(0x1F4A1..0x1F4FF, 0x1F500..0x1F53D)
    )
    private val grid = GridView(ctx)
    private var items: List<String> = emptyList()
    private val tabs = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.field)
        grid.numColumns = 8; grid.verticalSpacing = ctx.dp(4f); grid.setPadding(ctx.dp(6f), ctx.dp(6f), ctx.dp(6f), 0)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val tv = (cv as? TextView) ?: TextView(context).apply { textSize = 26f; gravity = Gravity.CENTER; setPadding(0, context.dp(4f), 0, context.dp(4f)) }
                tv.text = items[i]; Emo.apply(tv)
                return tv
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ -> onPick(items[pos]) }
        addView(grid, LayoutParams(-1, 0, 1f))
        tabs.orientation = HORIZONTAL
        cats.forEachIndexed { i, c ->
            tabs.addView(TextView(ctx).apply { text = c.first; textSize = 22f; gravity = Gravity.CENTER; setOnClickListener { show(i) } }, LayoutParams(0, ctx.dp(44f), 1f))
        }
        addView(tabs, LayoutParams(-1, -2))
        show(0)
    }

    private fun show(i: Int) {
        val p = Paint(); p.typeface = Emo.typeface(context)
        val out = ArrayList<String>()
        for (r in cats[i].second) for (cp in r) {
            val s = String(Character.toChars(cp))
            if (p.hasGlyph(s)) out.add(s)
        }
        items = out
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        grid.setSelection(0)
        for (k in 0 until tabs.childCount) tabs.getChildAt(k).alpha = if (k == i) 1f else 0.45f
    }
}

class Msg(val key: String, val from: String, val text: String, val ts: Long, var failed: Boolean = false, val reacts: Map<String, String> = emptyMap())
class Row(val type: Int, val msg: Msg? = null, val text: String = "", val tail: Boolean = false, val mine: Boolean = false, val topGap: Boolean = false)

class ChatScreen(act: MainActivity, private val cid: String, private val peer: String, private val peerName: String) : Screen(act) {
    private val me = Cfg.get(act, "uid")
    private var msgStream: Stream? = null
    private var metaStream: Stream? = null
    private var messages: List<Msg> = emptyList()
    private val pending = LinkedHashMap<String, Msg>()
    private var rows: List<Row> = emptyList()
    private var peerRead = 0L
    private var typing = false
    private var metaFirst = true
    private var lastPeerTyping = 0L
    private var lastTypingSent = 0L
    private var lastMarked = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideTyping = Runnable { typing = false; rebuild() }
    private val stopTyping = Runnable { setTyping(0L) }
    private lateinit var adapter: BaseAdapter
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var panel: EmojiPanel
    private var panelOn = false

    private fun sizeSp(): Float = when (Cfg.int(act, "tsize", 1)) { 0 -> 15f; 2 -> 20f else -> 17f }

    override fun build(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg) }
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(act.dp(8f), act.dp(6f), act.dp(8f), act.dp(6f)) }
        val bar = act.navBar(peerName, "‹ Messages", { act.pop() }, "📞", { act.startCall(cid, peer, peerName) }, { act.push(ProfileScreen(act, peer)) })
        root.addView(bar)
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getViewTypeCount() = 4
            override fun getItemViewType(i: Int) = rows[i].type
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val r = rows[i]
                return when (r.type) {
                    0 -> ((cv as? TextView) ?: TextView(act).apply { textSize = 12f; setTextColor(Theme.sub); gravity = Gravity.CENTER; setPadding(0, act.dp(12f), 0, act.dp(4f)) }).also { it.text = r.text }
                    2 -> ((cv as? TextView) ?: TextView(act).apply { textSize = 12f; setTextColor(Theme.sub); gravity = Gravity.END; setPadding(0, act.dp(2f), act.dp(18f), act.dp(2f)) }).also { it.text = r.text; it.setTextColor(if (r.text == "Not Delivered") 0xFFFF453A.toInt() else Theme.sub) }
                    3 -> bubbleRow(cv, null, "•••", false, true, true)
                    else -> bubbleRow(cv, r.msg, r.msg!!.text, r.mine, r.tail, r.topGap)
                }
            }
        }
        list = ListView(act).apply {
            divider = null; isVerticalScrollBarEnabled = false; adapter = this@ChatScreen.adapter
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL; isStackFromBottom = true
            setOnTouchListener { _, _ -> hidePanelAndKeyboard(); false }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(act.dp(8f), act.dp(6f), act.dp(8f), act.dp(8f)) }
        val emojiBtn = TextView(act).apply { text = "☺"; textSize = 26f; setTextColor(Theme.sub); gravity = Gravity.CENTER; setOnClickListener { togglePanel() } }
        bottom.addView(emojiBtn, LinearLayout.LayoutParams(act.dp(42f), act.dp(42f)))
        input = EditText(act).apply {
            hint = "Message"; setHintTextColor(Theme.sub); setTextColor(Theme.text); textSize = sizeSp(); maxLines = 5
            background = GradientDrawable().apply { setColor(Theme.field); cornerRadius = act.dpf(20f); setStroke(1, Theme.sep) }
            setPadding(act.dp(14f), act.dp(9f), act.dp(14f), act.dp(9f)); Emo.apply(this)
            setOnClickListener { setPanel(false) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { onTyped(e?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendBtn = TextView(act).apply {
            text = "↑"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.blue) }
            visibility = View.INVISIBLE; setOnClickListener { send() }
        }
        bottom.addView(sendBtn, LinearLayout.LayoutParams(act.dp(36f), act.dp(36f)).apply { leftMargin = act.dp(8f); bottomMargin = act.dp(3f) })
        root.addView(bottom)
        panel = EmojiPanel(act) { e -> input.append(e) }
        panel.visibility = View.GONE
        root.addView(panel, LinearLayout.LayoutParams(-1, act.dp(260f)))
        return root
    }

    private fun bubbleRow(cv: View?, m: Msg?, text: String, mine: Boolean, tail: Boolean, gap: Boolean): View {
        val row = (cv as? LinearLayout) ?: LinearLayout(act).apply { addView(BubbleView(act), LinearLayout.LayoutParams(-2, -2)) }
        val bv = row.getChildAt(0) as BubbleView
        row.gravity = if (mine) Gravity.END else Gravity.START
        row.setPadding(act.dp(10f), if (gap) act.dp(8f) else act.dp(2f), act.dp(10f), 0)
        bv.set(text, mine, tail, sizeSp(), reactStr(m))
        row.setOnClickListener { if (m != null && m.failed) resend(m) }
        bv.setOnLongClickListener { if (m != null) { showPopup(bv, m); true } else false }
        return row
    }

    // ---------------------------------------------------------------- data
    override fun onShow() {
        if (msgStream == null) {
            msgStream = Stream(act, "/chats/$cid/messages", "orderBy=%22%24key%22&limitToLast=100") { tree ->
                val out = ArrayList<Msg>()
                if (tree != null) {
                    val ks = tree.keys()
                    while (ks.hasNext()) {
                        val k = ks.next(); val o = tree.optJSONObject(k) ?: continue
                        val rm = HashMap<String, String>()
                        val ro = o.optJSONObject("r")
                        if (ro != null) { val rk = ro.keys(); while (rk.hasNext()) { val u = rk.next(); rm[u] = ro.optString(u) } }
                        out.add(Msg(k, o.optString("from"), o.optString("text"), o.optLong("ts"), false, rm))
                        pending.remove(k)
                    }
                }
                out.sortBy { it.key }
                messages = out
                rebuild()
                markRead()
            }.also { it.start() }
            metaStream = Stream(act, "/chats/$cid/meta", "") { tree ->
                peerRead = tree?.optJSONObject("read")?.optLong(peer) ?: 0L
                val tv = tree?.optJSONObject("typing")?.optLong(peer) ?: 0L
                if (metaFirst) { metaFirst = false; lastPeerTyping = tv }
                else if (tv != 0L && tv != lastPeerTyping) {
                    typing = true; handler.removeCallbacks(hideTyping); handler.postDelayed(hideTyping, 6000)
                } else if (tv == 0L) { typing = false }
                lastPeerTyping = tv
                rebuild()
            }.also { it.start() }
        }
    }

    override fun onPop() {
        msgStream?.close(); metaStream?.close()
        handler.removeCallbacksAndMessages(null)
        setTyping(0L)
    }

    private fun header(ts: Long): String {
        val d = Date(ts); val f = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        return if (f.format(d) == f.format(Date())) "Today $t" else SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(d) + " $t"
    }

    private fun rebuild() {
        val all = ArrayList<Msg>(messages)
        for (p in pending.values) if (all.none { it.key == p.key }) all.add(p)
        all.sortBy { it.key }
        val out = ArrayList<Row>()
        var prevTs = 0L; var prevFrom = ""
        val gapMs = 15 * 60 * 1000L
        for ((i, m) in all.withIndex()) {
            val ts = if (m.ts > 0L) m.ts else System.currentTimeMillis()
            if (prevTs == 0L || ts - prevTs > gapMs) out.add(Row(0, text = header(ts)))
            val next = all.getOrNull(i + 1)
            val tail = next == null || next.from != m.from || (next.ts > 0L && next.ts - ts > gapMs)
            out.add(Row(1, m, tail = tail, mine = m.from == me, topGap = prevFrom != m.from || prevTs == 0L || ts - prevTs > gapMs))
            prevTs = ts; prevFrom = m.from
        }
        val last = all.lastOrNull()
        if (last != null && last.from == me) {
            val st = when {
                last.failed -> "Not Delivered"
                pending.containsKey(last.key) -> "Sending…"
                peerRead > 0L && peerRead >= last.ts -> "Read"
                else -> "Delivered"
            }
            out.add(Row(2, text = st))
        }
        if (typing) out.add(Row(3))
        rows = out
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private val sv: JSONObject get() = JSONObject().put(".sv", "timestamp")

    private fun markRead() {
        val newest = messages.filter { it.from == peer }.maxOfOrNull { it.ts } ?: return
        if (newest <= lastMarked) return
        lastMarked = newest
        Net.bg { Db.patch(act, "/", JSONObject().put("userChats/$me/$cid/read", sv).put("chats/$cid/meta/read/$me", sv).toString()) }
    }

    private fun setTyping(v: Long) {
        Net.bg { Db.put(act, "/chats/$cid/meta/typing/$me", v.toString()) }
    }

    private fun onTyped(t: String) {
        sendBtn.visibility = if (t.isBlank()) View.INVISIBLE else View.VISIBLE
        val now = System.currentTimeMillis()
        if (t.isNotBlank() && now - lastTypingSent > 2500L) { lastTypingSent = now; setTyping(now) }
        handler.removeCallbacks(stopTyping)
        if (t.isNotBlank()) handler.postDelayed(stopTyping, 3500)
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        val text = if (t.length > 2000) t.substring(0, 2000) else t
        val key = "%013d".format(System.currentTimeMillis()) + Integer.toString((Math.random() * 1295).toInt(), 36).padStart(2, '0')
        val m = Msg(key, me, text, 0L)
        pending[key] = m
        rebuild()
        deliver(m)
    }

    private fun resend(m: Msg) { m.failed = false; rebuild(); deliver(m) }

    private fun deliver(m: Msg) {
        Net.bg {
            val up = JSONObject()
            up.put("chats/$cid/messages/${m.key}", JSONObject().put("from", me).put("text", m.text).put("ts", sv))
            up.put("chats/$cid/meta/typing/$me", 0)
            for (u in listOf(me, peer)) {
                up.put("userChats/$u/$cid/lastText", m.text); up.put("userChats/$u/$cid/lastFrom", me); up.put("userChats/$u/$cid/lastTs", sv)
            }
            up.put("userChats/$me/$cid/read", sv)
            val ok = try { Db.patch(act, "/", up.toString()) } catch (_: Throwable) { false }
            if (!ok) Net.ui { m.failed = true; rebuild() }
        }
    }

    // ---------------------------------------------------------------- tekan lama pada gelembung
    private fun reactStr(m: Msg?): String {
        if (m == null || m.reacts.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        for (e in m.reacts.values) if (e.isNotEmpty()) counts[e] = (counts[e] ?: 0) + 1
        return counts.entries.joinToString("") { if (it.value > 1) it.key + it.value else it.key }
    }

    private fun showPopup(anchor: View, m: Msg) {
        var pw: PopupWindow? = null
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.card); cornerRadius = act.dpf(18f); setStroke(1, Theme.sep) }
            elevation = act.dpf(10f)
        }
        val rr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(8f), act.dp(8f), act.dp(8f), act.dp(8f)) }
        for (e in listOf("❤️", "👍", "😂", "‼️", "❓", "👎")) {
            rr.addView(TextView(act).apply {
                text = e; textSize = 26f; setPadding(act.dp(8f), act.dp(4f), act.dp(8f), act.dp(4f)); Emo.apply(this)
                setOnClickListener { react(m, e); pw?.dismiss() }
            })
        }
        box.addView(rr)
        fun item(label: String, color: Int, run: () -> Unit) {
            box.addView(View(act).apply { setBackgroundColor(Theme.sep) }, LinearLayout.LayoutParams(-1, 1))
            box.addView(TextView(act).apply {
                text = label; textSize = 16f; setTextColor(color); setPadding(act.dp(18f), act.dp(12f), act.dp(18f), act.dp(12f))
                setOnClickListener { pw?.dismiss(); run() }
            })
        }
        item("Copy", Theme.text) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.text))
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
        }
        if (m.from == me && !pending.containsKey(m.key)) item("Delete", 0xFFFF453A.toInt()) { Net.bg { Db.delete(act, "/chats/$cid/messages/${m.key}") } }
        val w = PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        pw = w
        w.isOutsideTouchable = true
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val sw = act.resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width / 2 - box.measuredWidth / 2).coerceIn(act.dp(8f), maxOf(act.dp(8f), sw - box.measuredWidth - act.dp(8f)))
        val y = (loc[1] - box.measuredHeight - act.dp(6f)).coerceAtLeast(act.dp(60f))
        w.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun react(m: Msg, e: String) {
        Net.bg {
            val path = "/chats/$cid/messages/${m.key}/r/$me"
            if (m.reacts[me] == e) Db.delete(act, path) else Db.put(act, path, JSONObject.quote(e))
        }
    }

    // ---------------------------------------------------------------- papan emoji
    private fun setPanel(on: Boolean) {
        panelOn = on
        panel.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun togglePanel() {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!panelOn) { imm.hideSoftInputFromWindow(input.windowToken, 0); setPanel(true) } else { setPanel(false); input.requestFocus(); imm.showSoftInput(input, 0) }
    }

    private fun hidePanelAndKeyboard() {
        if (panelOn) setPanel(false)
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }
}
