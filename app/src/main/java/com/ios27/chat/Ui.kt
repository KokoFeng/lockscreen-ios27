package com.ios27.chat

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

object Theme {
    var dark = false
    val bg: Int get() = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val grouped: Int get() = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val card: Int get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val text: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val sub: Int get() = 0xFF8E8E93.toInt()
    val sep: Int get() = if (dark) 0xFF38383A.toInt() else 0xFFD1D1D6.toInt()
    val blue: Int get() = 0xFF0A84FF.toInt()
    val bubbleIn: Int get() = if (dark) 0xFF26262A.toInt() else 0xFFE9E9EB.toInt()
    val field: Int get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.ripple(): RippleDrawable = RippleDrawable(ColorStateList.valueOf(0x33808080), null, ColorDrawable(Color.WHITE))

/** Aplikasi ini tidak menggambar di bawah status bar (agar papan ketik mendorong layar naik), jadi tidak perlu jarak tambahan. */
fun Context.statusBarH(): Int = 0

fun b64ToBitmap(s: String?): Bitmap? = try {
    if (s.isNullOrEmpty()) null else { val b = Base64.decode(s, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size) }
} catch (_: Throwable) { null }

/** Foto profil bulat; bila belum ada foto tampil inisial nama. */
class AvatarView(ctx: Context, private val sizeDp: Float) : View(ctx) {
    var name = ""
        set(v) { field = v; invalidate() }
    var bmp: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { val s = context.dp(sizeDp); setMeasuredDimension(s, s) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val b = bmp
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix(); val s = maxOf(w / b.width, w / b.height)
            m.setScale(s, s); m.postTranslate((w - b.width * s) / 2f, (w - b.height * s) / 2f); sh.setLocalMatrix(m)
            p.shader = sh; c.drawCircle(w / 2f, w / 2f, w / 2f, p); p.shader = null
        } else {
            p.shader = LinearGradient(0f, 0f, 0f, w, 0xFFA5A5AB.toInt(), 0xFF7C7C82.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(w / 2f, w / 2f, w / 2f, p); p.shader = null
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = w * 0.4f
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            val t = parts.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
            c.drawText(t, w / 2f, w / 2f - (p.ascent() + p.descent()) / 2f, p)
        }
    }
}

/** Emoji kustom: pengguna memilih berkas font emoji (.ttf) sendiri, seperti di KeyOS. */
object Emo {
    private var tf: Typeface? = null
    private var loaded = false
    fun file(c: Context) = File(c.filesDir, "emoji.ttf")
    fun enabled(c: Context) = Cfg.bool(c, "emoji_on", true)
    fun has(c: Context) = file(c).exists()

    fun typeface(c: Context): Typeface? {
        if (!enabled(c)) return null
        if (!loaded) { loaded = true; tf = try { if (file(c).exists()) Typeface.createFromFile(file(c)) else null } catch (_: Throwable) { null } }
        return tf
    }

    fun reload() { loaded = false; tf = null }

    fun import(c: Context, uri: Uri): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { i -> file(c).outputStream().use { o -> i.copyTo(o) } }
        Typeface.createFromFile(file(c)); reload(); true
    } catch (_: Throwable) { file(c).delete(); reload(); false }

    fun reset(c: Context) { file(c).delete(); reload() }
    fun apply(tv: TextView) { val t = typeface(tv.context); if (t != null) tv.typeface = t }
}

/** Pop-up bergaya iOS: kartu bulat transparan dengan tombol Cancel | OK. */
class IosDialog(private val act: Activity) {
    private class B(val label: String, val style: Int, val action: () -> Unit)
    private var title = ""
    private var msg: String? = null
    private val fields = ArrayList<EditText>()
    private val btns = ArrayList<B>()

    fun title(t: String) = apply { title = t }
    fun message(m: String?) = apply { msg = m }
    fun button(label: String, style: Int = 0, action: () -> Unit = {}) = apply { btns.add(B(label, style, action)) }

    fun field(hint: String, value: String = ""): EditText {
        val e = EditText(act).apply {
            this.hint = hint; setHintTextColor(Theme.sub); setTextColor(Theme.text); setText(value); textSize = 16f; isSingleLine = true
            background = GradientDrawable().apply {
                setColor(if (Theme.dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()); cornerRadius = act.dpf(10f)
                setStroke(1, if (Theme.dark) 0xFF48484A.toInt() else 0xFFD1D1D6.toInt())
            }
            setPadding(act.dp(12f), act.dp(8f), act.dp(12f), act.dp(8f))
        }
        fields.add(e)
        return e
    }

    fun show() {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(if (Theme.dark) 0xF22C2C2E.toInt() else 0xF2F7F7F7.toInt()); cornerRadius = act.dpf(26f) }
            clipToOutline = true
        }
        box.addView(TextView(act).apply {
            text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Theme.text)
            setPadding(act.dp(20f), act.dp(22f), act.dp(20f), act.dp(if (msg == null && fields.isEmpty()) 22f else 8f))
        })
        msg?.let { m ->
            box.addView(TextView(act).apply {
                text = m; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Theme.sub); setPadding(act.dp(20f), 0, act.dp(20f), act.dp(16f))
            })
        }
        fields.forEachIndexed { i, f ->
            box.addView(f, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(act.dp(16f), if (i == 0) act.dp(4f) else act.dp(8f), act.dp(16f), if (i == fields.size - 1) act.dp(18f) else 0)
            })
        }
        box.addView(View(act).apply { setBackgroundColor(Theme.sep) }, LinearLayout.LayoutParams(-1, 1))
        val stacked = btns.size > 2
        val row = LinearLayout(act).apply { orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        btns.forEachIndexed { i, b ->
            row.addView(TextView(act).apply {
                text = b.label; textSize = 17f; gravity = Gravity.CENTER
                typeface = if (b.style == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (b.style == 2) 0xFFFF453A.toInt() else Theme.blue)
                foreground = act.ripple()
                setOnClickListener { d.dismiss(); b.action() }
            }, if (stacked) LinearLayout.LayoutParams(-1, act.dp(50f)) else LinearLayout.LayoutParams(0, act.dp(50f), 1f))
            if (i < btns.size - 1) row.addView(View(act).apply { setBackgroundColor(Theme.sep) },
                if (stacked) LinearLayout.LayoutParams(-1, 1) else LinearLayout.LayoutParams(1, -1))
        }
        box.addView(row)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setDimAmount(0.35f)
            setLayout(act.dp(300f), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
    }
}
