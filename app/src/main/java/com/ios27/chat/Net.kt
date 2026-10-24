package com.ios27.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Pengaturan tersimpan: kunci Firebase, alamat database, sesi masuk. */
object Cfg {
    private fun sp(c: Context) = c.getSharedPreferences("chat", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun set(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun long(c: Context, k: String, d: Long = 0L) = sp(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) { sp(c).edit().putLong(k, v).apply() }
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }
    fun configured(c: Context) = get(c, "apikey").isNotBlank() && get(c, "dburl").isNotBlank()
    fun signedIn(c: Context) = get(c, "refresh").isNotBlank() && get(c, "uid").isNotBlank()
}

object Net {
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    fun bg(f: () -> Unit) { pool.execute { try { f() } catch (_: Throwable) { } } }
    fun ui(f: () -> Unit) { main.post { f() } }

    class Resp(val code: Int, val body: String)

    fun call(method: String, url: String, body: String? = null, form: Boolean = false): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") {
                try { c.requestMethod = "PATCH" } catch (_: java.net.ProtocolException) {
                    c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                }
            } else c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 20000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            return Resp(code, s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
}

/** Akun lewat Firebase Authentication (username dipetakan ke email palsu, tanpa verifikasi). */
object Auth {
    fun email(user: String) = user.trim().lowercase() + "@ios27chat.app"
    fun validUser(user: String) = Regex("^[a-z0-9_.]{3,20}$").matches(user.trim().lowercase())

    private fun key(c: Context) = Cfg.get(c, "apikey")

    private fun friendly(body: String): String {
        val m = try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Throwable) { "" }
        return when {
            m.startsWith("EMAIL_EXISTS") -> "That username is already taken."
            m.startsWith("WEAK_PASSWORD") -> "Password must be at least 6 characters."
            m.contains("INVALID") || m.startsWith("EMAIL_NOT_FOUND") -> "Wrong username or password."
            m.startsWith("OPERATION_NOT_ALLOWED") -> "Turn on Email/Password sign-in in your Firebase project."
            m.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Try again later."
            m.contains("API key") || m.contains("API_KEY") -> "The API key is not valid. Check Server setup."
            m.isNotEmpty() -> m
            else -> "Something went wrong."
        }
    }

    private fun save(c: Context, j: JSONObject, user: String) {
        Cfg.set(c, "idtoken", j.getString("idToken")); Cfg.set(c, "refresh", j.getString("refreshToken"))
        Cfg.set(c, "uid", j.getString("localId")); Cfg.set(c, "user", user.trim().lowercase())
        Cfg.setLong(c, "exp", System.currentTimeMillis() + j.optString("expiresIn", "3600").toLong() * 1000L)
    }

    /** cb(pesanError atau null). Dipanggil di thread utama. */
    fun signUp(c: Context, user: String, pass: String, name: String, cb: (String?) -> Unit) = Net.bg {
        val err: String? = try {
            val r = Net.call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${key(c)}",
                JSONObject().put("email", email(user)).put("password", pass).put("returnSecureToken", true).toString())
            if (r.code != 200) friendly(r.body) else {
                save(c, JSONObject(r.body), user)
                val uid = Cfg.get(c, "uid"); val u = user.trim().lowercase(); val nm = name.trim().ifEmpty { u }
                Cfg.set(c, "name", nm)
                val ok1 = Db.put(c, "/usernames/$u", "\"$uid\"")
                val ok2 = Db.put(c, "/users/$uid", JSONObject().put("username", u).put("name", nm).toString())
                if (ok1 && ok2) null else "Account created, but the database rejected the profile. Check the security rules."
            }
        } catch (_: Throwable) { "No connection. Check your internet." }
        Net.ui { cb(err) }
    }

    fun signIn(c: Context, user: String, pass: String, cb: (String?) -> Unit) = Net.bg {
        val err: String? = try {
            val r = Net.call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${key(c)}",
                JSONObject().put("email", email(user)).put("password", pass).put("returnSecureToken", true).toString())
            if (r.code != 200) friendly(r.body) else {
                save(c, JSONObject(r.body), user)
                val p = Db.get(c, "/users/${Cfg.get(c, "uid")}") as? JSONObject
                Cfg.set(c, "name", p?.optString("name") ?: user.trim().lowercase())
                null
            }
        } catch (_: Throwable) { "No connection. Check your internet." }
        Net.ui { cb(err) }
    }

    /** Token yang masih berlaku (diperbarui otomatis). Panggil dari thread latar. */
    @Synchronized
    fun token(c: Context): String? {
        if (!Cfg.signedIn(c)) return null
        if (System.currentTimeMillis() < Cfg.long(c, "exp") - 60000L && Cfg.get(c, "idtoken").isNotEmpty()) return Cfg.get(c, "idtoken")
        return try {
            val r = Net.call("POST", "https://securetoken.googleapis.com/v1/token?key=${key(c)}",
                "grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(Cfg.get(c, "refresh"), "UTF-8"), true)
            if (r.code != 200) null else {
                val j = JSONObject(r.body)
                Cfg.set(c, "idtoken", j.getString("id_token")); Cfg.set(c, "refresh", j.getString("refresh_token"))
                Cfg.setLong(c, "exp", System.currentTimeMillis() + j.optString("expires_in", "3600").toLong() * 1000L)
                j.getString("id_token")
            }
        } catch (_: Throwable) { Cfg.get(c, "idtoken").ifEmpty { null } }
    }

    fun signOut(c: Context) {
        for (k in listOf("idtoken", "refresh", "uid", "user", "name")) Cfg.set(c, k, "")
        Cfg.setLong(c, "exp", 0L)
    }
}

/** Akses Realtime Database lewat REST (tanpa SDK). */
object Db {
    fun base(c: Context) = Cfg.get(c, "dburl").trim().trimEnd('/')

    fun url(c: Context, path: String, query: String = ""): String? {
        val t = Auth.token(c) ?: return null
        return base(c) + path + ".json?auth=" + t + (if (query.isEmpty()) "" else "&$query")
    }

    fun get(c: Context, path: String): Any? {
        val u = url(c, path) ?: return null
        val r = Net.call("GET", u)
        if (r.code != 200) return null
        val v = JSONTokener(r.body).nextValue()
        return if (v == JSONObject.NULL) null else v
    }

    fun put(c: Context, path: String, json: String): Boolean {
        val u = url(c, path) ?: return false
        return Net.call("PUT", u, json).code == 200
    }

    fun delete(c: Context, path: String): Boolean {
        val u = url(c, path) ?: return false
        return Net.call("DELETE", u).code == 200
    }

    fun patch(c: Context, path: String, json: String): Boolean {
        val u = url(c, path) ?: return false
        return Net.call("PATCH", u, json).code == 200
    }
}

/**
 * Mendengarkan perubahan data secara langsung (Server-Sent Events dari Realtime Database).
 * Hasil berupa salinan pohon data (JSONObject atau null) yang dikirim ke thread utama.
 */
class Stream(private val c: Context, private val path: String, private var query: String, private val onChange: (JSONObject?) -> Unit) {
    @Volatile private var closed = false
    private var conn: HttpURLConnection? = null
    private var root: Any? = null

    fun start() { Net.bg { loop() } }
    fun close() { closed = true; try { conn?.disconnect() } catch (_: Throwable) { } }

    private fun loop() {
        while (!closed) {
            try {
                val u = Db.url(c, path, query)
                if (u == null) { Thread.sleep(3000); continue }
                val cn = URL(u).openConnection() as HttpURLConnection
                conn = cn
                cn.setRequestProperty("Accept", "text/event-stream")
                cn.connectTimeout = 15000; cn.readTimeout = 0; cn.instanceFollowRedirects = true
                if (cn.responseCode != 200) {
                    if (query.isNotEmpty() && cn.responseCode == 400) query = ""
                    Thread.sleep(3000); continue
                }
                root = null
                val r = cn.inputStream.bufferedReader()
                var ev = ""; var data = ""
                while (!closed) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("event:") -> ev = line.substring(6).trim()
                        line.startsWith("data:") -> data = line.substring(5).trim()
                        line.isEmpty() -> { if (ev.isNotEmpty()) handle(ev, data); ev = ""; data = "" }
                    }
                }
            } catch (_: Throwable) { }
            if (!closed) { try { Thread.sleep(2000) } catch (_: Throwable) { } }
        }
    }

    private fun handle(ev: String, data: String) {
        if (ev == "cancel" || ev == "auth_revoked") throw RuntimeException("reconnect")
        if (ev != "put" && ev != "patch") return
        val j = JSONObject(data)
        val p = j.optString("path", "/")
        val v = j.opt("data")
        if (ev == "put") root = setAt(root, p, v)
        else if (v is JSONObject) {
            val keys = v.keys()
            while (keys.hasNext()) { val k = keys.next(); root = setAt(root, (if (p == "/") "" else p) + "/" + k, v.opt(k)) }
        }
        val copy: JSONObject? = (root as? JSONObject)?.let { JSONObject(it.toString()) }
        Net.ui { if (!closed) onChange(copy) }
    }

    private fun setAt(cur: Any?, path: String, value: Any?): Any? {
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        val v: Any? = if (value == null || value == JSONObject.NULL) null else value
        if (segs.isEmpty()) return v
        val top = (cur as? JSONObject) ?: JSONObject()
        var o = top
        for (i in 0 until segs.size - 1) {
            val child = o.optJSONObject(segs[i]) ?: JSONObject().also { o.put(segs[i], it) }
            o = child
        }
        val last = segs.last()
        if (v == null) o.remove(last) else o.put(last, v)
        return top
    }
}

/** Cache profil teman (nama dan foto). */
object Users {
    private val cache = HashMap<String, JSONObject>()
    fun cached(uid: String): JSONObject? = cache[uid]
    fun fetch(c: Context, uid: String, cb: (JSONObject?) -> Unit) {
        val hit = cache[uid]
        if (hit != null) { cb(hit); return }
        Net.bg {
            val o = Db.get(c, "/users/$uid") as? JSONObject
            if (o != null) cache[uid] = o
            Net.ui { cb(o) }
        }
    }
    fun forget(uid: String) { cache.remove(uid) }
}
