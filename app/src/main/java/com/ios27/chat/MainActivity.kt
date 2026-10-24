package com.ios27.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val stack = ArrayList<Screen>()
    private lateinit var root: FrameLayout
    private var list: ListScreen? = null
    private var pendingMic: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(Theme.bg))
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        var f = 0
        if (!Theme.dark) { f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
        window.decorView.systemUiVisibility = f
        root = FrameLayout(this).apply { setBackgroundColor(Theme.bg) }
        setContentView(root)
        startFlow()
        handleLink(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleLink(i) }

    /** Tautan ios27chat://u/NAMA dari kode QR atau pesan. */
    private fun handleLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "ios27chat") return
        val u = Qr.parse(d.toString()) ?: return
        if (Cfg.signedIn(this) && Cfg.configured(this)) lookupUser(u) else Toast.makeText(this, "Sign in first, then open the link again.", Toast.LENGTH_LONG).show()
    }

    private fun startFlow() {
        stack.clear()
        if (Cfg.signedIn(this) && Cfg.configured(this)) {
            val l = ListScreen(this); list = l; push(l); CallMgr.watch(this)
        } else push(AuthScreen(this))
    }

    fun push(s: Screen) { stack.lastOrNull()?.onHide(); stack.add(s); show(s) }

    fun pop() {
        if (stack.size > 1) { val s = stack.removeAt(stack.size - 1); s.onHide(); s.onPop(); show(stack.last()) } else finish()
    }

    private fun show(s: Screen) {
        root.removeAllViews()
        root.addView(s.view(), FrameLayout.LayoutParams(-1, -1))
        s.onShow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (stack.lastOrNull()?.onBack() == true) return; pop() }

    override fun onDestroy() { CallMgr.shutdown(this); super.onDestroy() }

    fun afterAuth() { list?.stop(); list = null; startFlow() }

    fun signOut() {
        CallMgr.shutdown(this)
        list?.stop(); list = null
        Auth.signOut(this)
        startFlow()
    }

    fun lookupUser(username: String) {
        Net.bg {
            val uid = Db.get(this, "/usernames/$username") as? String
            Net.ui { if (uid == null) Toast.makeText(this, "No one with that username.", Toast.LENGTH_SHORT).show() else push(ProfileScreen(this, uid)) }
        }
    }

    /** Pastikan percakapan dengan teman sudah ada, lalu panggil [then] dengan id percakapan. */
    fun ensureChat(peerUid: String, peerName: String, peerUser: String, then: (String) -> Unit) {
        val me = Cfg.get(this, "uid")
        val cid = listOf(me, peerUid).sorted().joinToString("_")
        Net.bg {
            val ok1 = Db.put(this, "/chats/$cid/members", JSONObject().put(me, true).put(peerUid, true).toString())
            val up = JSONObject()
            up.put("userChats/$me/$cid/with", peerUid); up.put("userChats/$me/$cid/name", peerName); up.put("userChats/$me/$cid/user", peerUser)
            up.put("userChats/$peerUid/$cid/with", me); up.put("userChats/$peerUid/$cid/name", Cfg.get(this, "name")); up.put("userChats/$peerUid/$cid/user", Cfg.get(this, "user"))
            val ok = ok1 && Db.patch(this, "/", up.toString())
            Net.ui {
                if (ok) then(cid) else Toast.makeText(this, "Could not reach the chat. Check the Firebase security rules.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openChat(peerUid: String, peerName: String, peerUser: String) {
        ensureChat(peerUid, peerName, peerUser) { cid -> push(ChatScreen(this, cid, peerUid, peerName)) }
    }

    fun startCall(cid: String, peerUid: String, name: String) {
        if (CallMgr.state != "idle") { Toast.makeText(this, "You are already in a call.", Toast.LENGTH_SHORT).show(); return }
        withMic { CallMgr.startOutgoing(this, cid, peerUid, name); push(CallScreen(this)) }
    }

    /** Jalankan [action] setelah izin mikrofon diberikan. */
    fun withMic(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingMic = action; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 31) {
            val a = pendingMic; pendingMic = null
            if (granted) a?.invoke() else Toast.makeText(this, "Microphone permission is needed for calls.", Toast.LENGTH_LONG).show()
        }
        stack.lastOrNull()?.onPermission(requestCode, granted)
    }

    fun pickImage(req: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, req)
    }

    fun pickFont(req: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        stack.lastOrNull()?.onResult(rc, res, data)
    }
}
