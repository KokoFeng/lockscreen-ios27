package com.ios27.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class Screen(val act: MainActivity) {
    private var v: View? = null
    fun view(): View = v ?: build().also { v = it }
    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onPop() {}
    open fun onBack(): Boolean = false
    open fun onPermission(req: Int, granted: Boolean) {}
    open fun onResult(req: Int, res: Int, data: Intent?) {}
}

val RULES = """{
  "rules": {
    "usernames": {
      ".read": "auth != null",
      "${'$'}u": { ".write": "auth != null && !data.exists() && newData.val() === auth.uid" }
    },
    "users": {
      ".read": "auth != null",
      "${'$'}uid": { ".write": "auth != null && auth.uid === ${'$'}uid" }
    },
    "chats": {
      "${'$'}cid": {
        ".read": "auth != null && data.child('members').child(auth.uid).exists()",
        ".write": "auth != null && ((!data.exists() && newData.child('members').child(auth.uid).exists()) || data.child('members').child(auth.uid).exists())"
      }
    },
    "calls": {
      "${'$'}cid": {
        ".read": "auth != null && root.child('chats').child(${'$'}cid).child('members').child(auth.uid).exists()",
        ".write": "auth != null && root.child('chats').child(${'$'}cid).child('members').child(auth.uid).exists()"
      }
    },
    "userCalls": {
      "${'$'}uid": {
        ".read": "auth != null && auth.uid === ${'$'}uid",
        "${'$'}cid": {
          ".write": "auth != null && (auth.uid === ${'$'}uid || root.child('chats').child(${'$'}cid).child('members').child(auth.uid).exists())"
        }
      }
    },
    "userChats": {
      "${'$'}uid": {
        ".read": "auth != null && auth.uid === ${'$'}uid",
        "${'$'}cid": {
          ".write": "auth != null && (auth.uid === ${'$'}uid || root.child('chats').child(${'$'}cid).child('members').child(auth.uid).exists())"
        }
      }
    }
  }
}"""

// ------------------------------------------------------------------ pembantu tampilan
fun Context.navBar(title: String, left: String?, onLeft: (() -> Unit)?, right: String? = null, onRight: (() -> Unit)? = null, onTitle: (() -> Unit)? = null): FrameLayout {
    val bar = FrameLayout(this).apply { setPadding(dp(8f), statusBarH() + dp(6f), dp(8f), dp(6f)) }
    if (left != null) bar.addView(TextView(this).apply {
        text = left; textSize = if (left.length == 1) 26f else 17f; setTextColor(Theme.blue); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f), 0, dp(10f), 0)
        setOnClickListener { onLeft?.invoke() }
    }, FrameLayout.LayoutParams(-2, dp(40f), Gravity.START))
    bar.addView(TextView(this).apply { text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text); gravity = Gravity.CENTER; if (onTitle != null) setOnClickListener { onTitle() } },
        FrameLayout.LayoutParams(-2, dp(40f), Gravity.CENTER_HORIZONTAL))
    if (right != null) bar.addView(TextView(this).apply {
        text = right; textSize = 17f; setTextColor(Theme.blue); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f), 0, dp(10f), 0)
        setOnClickListener { onRight?.invoke() }
    }, FrameLayout.LayoutParams(-2, dp(40f), Gravity.END))
    return bar
}

fun Context.pill(hint: String, value: String = "", password: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint; setHintTextColor(Theme.sub); setTextColor(Theme.text); setText(value); textSize = 17f; isSingleLine = true
    if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    background = GradientDrawable().apply { setColor(Theme.field); cornerRadius = dpf(14f) }
    setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
}

fun Context.bigButton(t: String, onClick: () -> Unit, filled: Boolean = true): TextView = TextView(this).apply {
    text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
    setTextColor(if (filled) Color.WHITE else Theme.blue)
    background = GradientDrawable().apply { setColor(if (filled) Theme.blue else Theme.field); cornerRadius = dpf(14f) }
    setPadding(0, dp(15f), 0, dp(15f)); setOnClickListener { onClick() }
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply { setColor(Theme.card); cornerRadius = dpf(16f) }
    clipToOutline = true
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16f), dp(8f), dp(16f), dp(8f)) }
}

fun Context.sep(): View = View(this).apply { setBackgroundColor(Theme.sep); layoutParams = LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16f) } }

fun Context.rowView(title: String, value: String? = null, valueColor: Int = Theme.sub, chevron: Boolean = false, color: Int = Theme.text, onClick: (() -> Unit)? = null): LinearLayout {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(50f); setPadding(dp(16f), dp(8f), dp(14f), dp(8f)) }
    r.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(color) }, LinearLayout.LayoutParams(0, -2, 1f))
    if (value != null) r.addView(TextView(this).apply { text = value; textSize = 16f; setTextColor(valueColor); maxLines = 1 })
    if (chevron) r.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(Theme.sep); setPadding(dp(8f), 0, 0, 0) })
    if (onClick != null) { r.setOnClickListener { onClick() }; r.foreground = ripple() }
    return r
}

fun Context.addRows(c: LinearLayout, vararg rows: View) {
    rows.forEachIndexed { i, v -> c.addView(v); if (i < rows.size - 1) c.addView(sep()) }
}

fun Context.small(t: String, upper: Boolean = true): TextView = TextView(this).apply {
    text = if (upper) t.uppercase() else t; textSize = 13f; setTextColor(Theme.sub); setPadding(dp(32f), dp(16f), dp(16f), dp(4f))
}

fun Context.foot(t: String): TextView = TextView(this).apply { text = t; textSize = 13f; setTextColor(Theme.sub); setPadding(dp(32f), dp(4f), dp(32f), dp(8f)) }

fun clock(ts: Long): String {
    if (ts <= 0L) return ""
    val d = Date(ts)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return if (today.format(d) == today.format(Date())) SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(d)
}

// ------------------------------------------------------------------ masuk / buat akun
class AuthScreen(act: MainActivity) : Screen(act) {
    private var signup = false
    private lateinit var holder: LinearLayout
    private val user = act.pill("Username")
    private val pass = act.pill("Password", password = true)
    private val name = act.pill("Display name (optional)")
    private val err = TextView(act).apply { textSize = 14f; setTextColor(0xFFFF453A.toInt()); setPadding(act.dp(4f), act.dp(10f), act.dp(4f), 0) }
    private var busy = false

    override fun build(): View {
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(24f), act.statusBarH() + act.dp(36f), act.dp(24f), act.dp(40f)) }
        col.addView(TextView(act).apply { text = "Messages"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text) })
        col.addView(TextView(act).apply {
            text = "Chat with friends who use this app. Messages use your internet data, not SMS credit."
            textSize = 15f; setTextColor(Theme.sub); setPadding(0, act.dp(4f), 0, act.dp(20f))
        })
        holder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder)
        sv.addView(col)
        render()
        return sv
    }

    private fun render() {
        holder.removeAllViews()
        val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; background = GradientDrawable().apply { setColor(Theme.field); cornerRadius = act.dpf(12f) }; setPadding(act.dp(3f), act.dp(3f), act.dp(3f), act.dp(3f)) }
        for ((label, isUp) in listOf("Sign In" to false, "Create Account" to true)) {
            seg.addView(TextView(act).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = if (signup == isUp) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Theme.text)
                background = GradientDrawable().apply { setColor(if (signup == isUp) Theme.card else Color.TRANSPARENT); cornerRadius = act.dpf(10f) }
                setPadding(0, act.dp(9f), 0, act.dp(9f)); setOnClickListener { signup = isUp; err.text = ""; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        holder.addView(seg)
        for (f in listOf(user, pass, name)) (f.parent as? ViewGroup)?.removeView(f)
        holder.addView(user, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(16f) })
        holder.addView(pass, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(10f) })
        if (signup) holder.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(10f) })
        (err.parent as? ViewGroup)?.removeView(err)
        holder.addView(err)
        holder.addView(act.bigButton(if (signup) "Create Account" else "Sign In", { submit() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(16f) })
        holder.addView(act.bigButton("Continue with Google", {
            IosDialog(act).title("Google sign-in").message("Google sign-in needs an extra Google Cloud setup (an OAuth client). It is not turned on in this version. Use a username and password for now.")
                .button("OK", 1).show()
        }, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(10f) })
        holder.addView(TextView(act).apply {
            text = "Server setup"; textSize = 15f; setTextColor(Theme.blue); gravity = Gravity.CENTER; setPadding(0, act.dp(20f), 0, 0)
            setOnClickListener { act.push(SetupScreen(act)) }
        })
    }

    private fun submit() {
        if (busy) return
        if (!Cfg.configured(act)) { err.text = "Set up the server first (tap Server setup)."; return }
        val u = user.text.toString().trim().lowercase(); val p = pass.text.toString()
        if (!Auth.validUser(u)) { err.text = "Username: 3 to 20 letters, numbers, dots or underscores."; return }
        if (p.length < 6) { err.text = "Password must be at least 6 characters."; return }
        busy = true; err.text = "Please wait…"; err.setTextColor(Theme.sub)
        val done: (String?) -> Unit = { e ->
            busy = false
            if (e == null) act.afterAuth() else { err.setTextColor(0xFFFF453A.toInt()); err.text = e }
        }
        if (signup) Auth.signUp(act, u, p, name.text.toString(), done) else Auth.signIn(act, u, p, done)
    }
}

// ------------------------------------------------------------------ pengaturan server
class SetupScreen(act: MainActivity) : Screen(act) {
    override fun build(): View {
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, act.dp(40f)) }
        col.addView(act.navBar("Server setup", "‹ Back", { act.pop() }))
        col.addView(TextView(act).apply {
            text = "Messages are stored in your own free Firebase project, so no one else runs a server for you.\n\n" +
                "1. Open console.firebase.google.com and create a project.\n" +
                "2. Build > Authentication > Get started > Sign-in method > turn on Email/Password.\n" +
                "3. Build > Realtime Database > Create database. Then open the Rules tab, paste the rules from the button below, and Publish.\n" +
                "4. Project settings (gear) > General > copy the Web API Key.\n" +
                "5. On the Realtime Database page, copy the database URL (looks like https://NAME-default-rtdb.firebaseio.com).\n" +
                "6. Paste both below."
            textSize = 15f; setTextColor(Theme.text); setPadding(act.dp(24f), act.dp(8f), act.dp(24f), act.dp(12f))
        })
        val key = act.pill("Web API Key", Cfg.get(act, "apikey"))
        val url = act.pill("Database URL", Cfg.get(act, "dburl"))
        col.addView(key, LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(url, LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(act.bigButton("Copy security rules", {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("rules", RULES))
            Toast.makeText(act, "Rules copied", Toast.LENGTH_SHORT).show()
        }, false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(act.bigButton("Save", {
            val k = key.text.toString().trim(); val u = url.text.toString().trim()
            if (k.isEmpty() || !u.startsWith("https://")) Toast.makeText(act, "Enter the API key and a database URL starting with https://", Toast.LENGTH_LONG).show()
            else { Cfg.set(act, "apikey", k); Cfg.set(act, "dburl", u); act.pop() }
        }), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), 0) })
        sv.addView(col)
        return sv
    }
}

// ------------------------------------------------------------------ daftar percakapan
class Conv(val cid: String, val with: String, val name: String, val last: String, val ts: Long, val from: String, val read: Long)

class ListScreen(act: MainActivity) : Screen(act) {
    private var convs: List<Conv> = emptyList()
    private var stream: Stream? = null
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView

    override fun build(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(act.navBar("", "☰", { act.push(SettingsScreen(act)) }, "✎ New", { act.push(NewChatScreen(act)) }))
        root.addView(TextView(act).apply { text = "Messages"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text); setPadding(act.dp(20f), 0, 0, act.dp(8f)) })
        empty = TextView(act).apply { text = "No conversations yet.\nTap New to find a friend by username."; textSize = 16f; setTextColor(Theme.sub); gravity = Gravity.CENTER; setPadding(act.dp(32f), act.dp(60f), act.dp(32f), 0) }
        adapter = object : BaseAdapter() {
            override fun getCount() = convs.size
            override fun getItem(i: Int) = convs[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val c = convs[i]
                val row = (cv as? LinearLayout) ?: newRow()
                val av = row.getChildAt(0) as AvatarView
                av.name = c.name
                val u = Users.cached(c.with)
                av.bmp = b64ToBitmap(u?.optString("avatar"))
                if (u == null) Users.fetch(act, c.with) { adapter.notifyDataSetChanged() }
                val col = row.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = c.name
                val lt = col.getChildAt(1) as TextView
                lt.text = if (c.last.isEmpty()) "No messages yet" else (if (c.from == Cfg.get(act, "uid")) "You: " else "") + c.last
                Emo.apply(lt)
                (row.getChildAt(2) as TextView).text = clock(c.ts)
                row.getChildAt(3).visibility = if (c.from.isNotEmpty() && c.from != Cfg.get(act, "uid") && c.ts > c.read) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener { act.push(ChatScreen(act, c.cid, c.with, c.name)) }
                return row
            }
        }
        val lv = ListView(act).apply { divider = null; adapter = this@ListScreen.adapter; isVerticalScrollBarEnabled = false }
        root.addView(empty)
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setBackgroundColor(Theme.bg)
        return root
    }

    private fun newRow(): LinearLayout {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(16f), act.dp(10f), act.dp(16f), act.dp(10f)); foreground = act.ripple() }
        row.addView(AvatarView(act, 52f), LinearLayout.LayoutParams(act.dp(52f), act.dp(52f)).apply { rightMargin = act.dp(12f) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text); maxLines = 1 })
        col.addView(TextView(act).apply { textSize = 15f; setTextColor(Theme.sub); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(act).apply { textSize = 14f; setTextColor(Theme.sub); setPadding(act.dp(8f), 0, act.dp(8f), 0) })
        row.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.blue) } }, LinearLayout.LayoutParams(act.dp(10f), act.dp(10f)))
        return row
    }

    override fun onShow() {
        if (stream == null) {
            val me = Cfg.get(act, "uid")
            stream = Stream(act, "/userChats/$me", "") { tree ->
                val out = ArrayList<Conv>()
                if (tree != null) {
                    val ks = tree.keys()
                    while (ks.hasNext()) {
                        val k = ks.next(); val o = tree.optJSONObject(k) ?: continue
                        out.add(Conv(k, o.optString("with"), o.optString("name", o.optString("user")), o.optString("lastText"), o.optLong("lastTs"), o.optString("lastFrom"), o.optLong("read")))
                    }
                }
                convs = out.sortedByDescending { it.ts }
                empty.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }.also { it.start() }
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    fun stop() { stream?.close(); stream = null }
}

// ------------------------------------------------------------------ pesan baru
class NewChatScreen(act: MainActivity) : Screen(act) {
    private lateinit var result: LinearLayout

    override fun build(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.navBar("New Message", "Cancel", { act.pop() }))
        val q = act.pill("Friend's username")
        col.addView(q, LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), act.dp(8f), act.dp(20f), act.dp(10f)) })
        result = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(act.bigButton("Search", { search(q.text.toString().trim().lowercase()) }), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(act.bigButton("Scan QR code", { act.push(ScanScreen(act)) }, false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(act.bigButton("My QR code", { act.push(MyQrScreen(act)) }, false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), 0, act.dp(20f), act.dp(10f)) })
        col.addView(result)
        col.setBackgroundColor(Theme.grouped)
        return col
    }

    private fun search(u: String) {
        result.removeAllViews()
        if (u.isEmpty()) return
        result.addView(act.foot("Searching…"))
        Net.bg {
            val uid = (Db.get(act, "/usernames/$u") as? String)
            val prof = if (uid != null) Db.get(act, "/users/$uid") as? JSONObject else null
            Net.ui {
                result.removeAllViews()
                if (uid == null || prof == null) { result.addView(act.foot("No one with that username.")); return@ui }
                if (uid == Cfg.get(act, "uid")) { result.addView(act.foot("That is you.")); return@ui }
                val nm = prof.optString("name", u)
                val c = act.card()
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(14f), act.dp(12f), act.dp(14f), act.dp(12f)); foreground = act.ripple() }
                row.addView(AvatarView(act, 46f).apply { name = nm; bmp = b64ToBitmap(prof.optString("avatar")) }, LinearLayout.LayoutParams(act.dp(46f), act.dp(46f)).apply { rightMargin = act.dp(12f) })
                val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(TextView(act).apply { text = nm; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text) })
                tx.addView(TextView(act).apply { text = "@$u"; textSize = 14f; setTextColor(Theme.sub) })
                row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(act).apply { text = "Message"; textSize = 16f; setTextColor(Theme.blue) })
                row.setOnClickListener { act.push(ProfileScreen(act, uid)) }
                c.addView(row); result.addView(c)
            }
        }
    }
}

// ------------------------------------------------------------------ pengaturan
class SettingsScreen(act: MainActivity) : Screen(act) {
    private lateinit var holder: LinearLayout

    override fun build(): View {
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(Theme.grouped) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, act.dp(40f)) }
        col.addView(act.navBar("Settings", "‹ Back", { act.pop() }))
        holder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder)
        sv.addView(col)
        render()
        return sv
    }

    override fun onShow() { if (::holder.isInitialized) render() }

    private fun switchRow(title: String, key: String, def: Boolean): LinearLayout {
        val sw = Switch2(act)
        sw.checked = Cfg.bool(act, key, def)
        sw.onToggle = { v -> Cfg.setBool(act, key, v); sw.checked = v; Emo.reload() }
        val r = act.rowView(title)
        r.addView(sw, LinearLayout.LayoutParams(act.dp(51f), act.dp(31f)))
        return r
    }

    private fun chips(title: String, names: List<String>, cur: Int, onSel: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16f), act.dp(10f), act.dp(14f), act.dp(12f)) }
        box.addView(TextView(act).apply { text = title; textSize = 17f; setTextColor(Theme.text) })
        val ch = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, act.dp(8f), 0, 0) }
        names.forEachIndexed { i, n ->
            ch.addView(TextView(act).apply {
                text = n; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (i == cur) Color.WHITE else Theme.sub)
                background = GradientDrawable().apply { setColor(if (i == cur) Theme.blue else Theme.field); cornerRadius = act.dpf(16f) }
                setPadding(0, act.dp(8f), 0, act.dp(8f)); setOnClickListener { onSel(i); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = act.dp(6f) })
        }
        box.addView(ch)
        return box
    }

    private fun render() {
        holder.removeAllViews()
        val me = Cfg.get(act, "uid")
        // profil
        val p = act.card()
        val prow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(16f), act.dp(14f), act.dp(16f), act.dp(14f)); foreground = act.ripple() }
        val prof = Users.cached(me)
        prow.addView(AvatarView(act, 64f).apply { name = Cfg.get(act, "name"); bmp = b64ToBitmap(prof?.optString("avatar")) }, LinearLayout.LayoutParams(act.dp(64f), act.dp(64f)).apply { rightMargin = act.dp(14f) })
        val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(TextView(act).apply { text = Cfg.get(act, "name"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text) })
        tx.addView(TextView(act).apply { text = "@" + Cfg.get(act, "user") + "  ·  tap to change photo"; textSize = 14f; setTextColor(Theme.sub) })
        prow.addView(tx)
        prow.setOnClickListener { act.pickImage(11) }
        p.addView(prow)
        holder.addView(p)
        if (prof == null) Users.fetch(act, me) { render() }
        val n = act.card()
        act.addRows(n, act.rowView("Display name", Cfg.get(act, "name"), chevron = true) {
            val d = IosDialog(act).title("Display name")
            val f = d.field("Name", Cfg.get(act, "name"))
            d.button("Cancel").button("Save", 1) {
                val nm = f.text.toString().trim()
                if (nm.isNotEmpty()) Net.bg {
                    if (Db.patch(act, "/users/$me", JSONObject().put("name", nm).toString())) { Cfg.set(act, "name", nm); Users.forget(me) }
                    Net.ui { render() }
                }
            }
            d.show()
        })
        holder.addView(n)
        val bioNow = Users.cached(me)?.optString("bio") ?: ""
        val pr = act.card()
        act.addRows(pr,
            act.rowView("Bio", if (bioNow.isEmpty()) "Add" else bioNow.take(24), chevron = true) {
                val d = IosDialog(act).title("Bio")
                val f = d.field("Tell friends about you (max 140)", bioNow)
                d.button("Cancel").button("Save", 1) {
                    val b = f.text.toString().trim().take(140)
                    Net.bg { Db.patch(act, "/users/$me", JSONObject().put("bio", b).toString()); Users.forget(me); Net.ui { render() } }
                }
                d.show()
            },
            act.rowView("My QR Code", chevron = true) { act.push(MyQrScreen(act)) },
            act.rowView("Scan QR Code", chevron = true) { act.push(ScanScreen(act)) },
            act.rowView("View my profile", chevron = true) { act.push(ProfileScreen(act, me)) })
        holder.addView(pr)

        // emoji
        holder.addView(act.small("Emoji"))
        val e = act.card()
        act.addRows(e,
            switchRow("Use custom emoji font", "emoji_on", true),
            act.rowView("Emoji font file", if (Emo.has(act)) "Custom (.ttf)" else "System emoji", chevron = true) { act.pickFont(12) },
            act.rowView("Remove custom emoji font", color = 0xFFFF453A.toInt()) { Emo.reset(act); render() })
        holder.addView(e)
        val prev = act.card()
        prev.addView(TextView(act).apply { text = "😀 😍 🎉 ❤️ 👍 🔥 😂 🙏"; textSize = 28f; setPadding(act.dp(16f), act.dp(14f), act.dp(16f), act.dp(14f)); setTextColor(Theme.text); Emo.apply(this) })
        holder.addView(prev)
        holder.addView(act.foot("Choose an emoji font file (for example an iOS-style emoji .ttf you own) and it is used for every message, the same way as in KeyOS. If the font lacks some emoji, the system ones fill in."))

        // teks
        holder.addView(act.small("Messages"))
        val t = act.card()
        act.addRows(t, chips("Text size", listOf("Small", "Medium", "Large"), Cfg.int(act, "tsize", 1)) { Cfg.setInt(act, "tsize", it) })
        holder.addView(t)

        // panggilan
        holder.addView(act.small("Calls"))
        val cc = act.card()
        act.addRows(cc, act.rowView("Relay (TURN) server", if (Cfg.get(act, "turn_url").isEmpty()) "Not set" else "Set", chevron = true) {
            val d = IosDialog(act).title("Relay (TURN) server")
            val u = d.field("turn:host:3478", Cfg.get(act, "turn_url"))
            val un = d.field("Username", Cfg.get(act, "turn_user"))
            val pw = d.field("Password", Cfg.get(act, "turn_pass"))
            d.button("Cancel").button("Save", 1) {
                Cfg.set(act, "turn_url", u.text.toString().trim()); Cfg.set(act, "turn_user", un.text.toString().trim()); Cfg.set(act, "turn_pass", pw.text.toString()); render()
            }
            d.show()
        })
        holder.addView(cc)
        holder.addView(act.foot("Calls connect phone to phone. On some mobile networks that needs a relay (TURN) server. Leave this empty to try without one. Calls ring only while the app is open."))

        // server dan akun
        holder.addView(act.small("Account"))
        val a = act.card()
        act.addRows(a,
            act.rowView("Server setup", chevron = true) { act.push(SetupScreen(act)) },
            act.rowView("Sign out", color = 0xFFFF453A.toInt()) {
                IosDialog(act).title("Sign out?").message("You can sign back in with your username and password.")
                    .button("Cancel").button("Sign Out", 2) { act.signOut() }.show()
            })
        holder.addView(a)
        holder.addView(act.small("About"))
        val ab = act.card()
        act.addRows(ab,
            act.rowView("Developer", "Feng iOS on Droid"),
            act.rowView("Support Developer", chevron = true) {
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sociabuzz.com/feng_ios/tribe"))) } catch (_: Exception) { }
            })
        holder.addView(ab)
        holder.addView(act.foot("Messages are stored in your own Firebase project and are not end-to-end encrypted."))
    }

    override fun onResult(req: Int, res: Int, data: Intent?) {
        if (res != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (req == 12) {
            val ok = Emo.import(act, uri)
            Toast.makeText(act, if (ok) "Emoji font set" else "This file is not a usable font", Toast.LENGTH_SHORT).show()
            render()
        } else if (req == 11) {
            val me = Cfg.get(act, "uid")
            Net.bg {
                val b64 = try { smallAvatar(uri) } catch (_: Throwable) { null }
                if (b64 != null && Db.patch(act, "/users/$me", JSONObject().put("avatar", b64).toString())) Users.forget(me)
                Net.ui { render() }
            }
        }
    }

    private fun smallAvatar(uri: Uri): String {
        val src = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw RuntimeException("x")
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, 160, 160, true)
        val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 80, bo)
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}

/** Saklar bergaya iOS. */
class Switch2(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null); setOnClickListener { onToggle?.invoke(!checked) } }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(51f), context.dp(31f))
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = android.graphics.Paint.Style.FILL
        p.color = if (checked) 0xFF34C759.toInt() else if (Theme.dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()
        c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, p)
        val r = h / 2f - context.dpf(2f); val cx = if (checked) w - h / 2f else h / 2f
        p.color = Color.WHITE; p.setShadowLayer(context.dpf(2f), 0f, context.dpf(1f), 0x44000000)
        c.drawCircle(cx, h / 2f, r, p); p.clearShadowLayer()
    }
}
