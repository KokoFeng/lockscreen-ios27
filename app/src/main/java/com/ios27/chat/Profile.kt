package com.ios27.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/** Kode QR berisi tautan ios27chat://u/USERNAME. */
object Qr {
    const val PREFIX = "ios27chat://u/"

    fun make(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun parse(t: String): String? {
        if (!t.startsWith(PREFIX)) return null
        val u = t.removePrefix(PREFIX).trim().lowercase()
        return if (Auth.validUser(u)) u else null
    }
}

// ---------------------------------------------------------------- profil teman
class ProfileScreen(act: MainActivity, private val uid: String) : Screen(act) {
    private lateinit var holder: LinearLayout

    override fun build(): View {
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(Theme.grouped) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, act.dp(40f)) }
        col.addView(act.navBar("Profile", "‹ Back", { act.pop() }))
        holder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(holder)
        sv.addView(col)
        holder.addView(act.foot("Loading…"))
        Users.forget(uid)
        Users.fetch(act, uid) { render(it) }
        return sv
    }

    private fun render(p: JSONObject?) {
        holder.removeAllViews()
        if (p == null) { holder.addView(act.foot("Could not load this profile.")); return }
        val me = Cfg.get(act, "uid")
        val name = p.optString("name", p.optString("username")); val user = p.optString("username"); val bio = p.optString("bio")
        holder.addView(AvatarView(act, 120f).apply { this.name = name; bmp = b64ToBitmap(p.optString("avatar")) },
            LinearLayout.LayoutParams(act.dp(120f), act.dp(120f)).apply { topMargin = act.dp(16f) })
        holder.addView(TextView(act).apply { text = name; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.text); gravity = Gravity.CENTER; setPadding(0, act.dp(12f), 0, 0) })
        holder.addView(TextView(act).apply { text = "@$user"; textSize = 15f; setTextColor(Theme.sub); gravity = Gravity.CENTER })
        val c = act.card()
        c.addView(TextView(act).apply {
            text = if (bio.isEmpty()) "No bio yet." else bio; textSize = 16f
            setTextColor(if (bio.isEmpty()) Theme.sub else Theme.text); setPadding(act.dp(16f), act.dp(14f), act.dp(16f), act.dp(14f))
        })
        holder.addView(c)
        val pad = LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), act.dp(4f), act.dp(20f), act.dp(8f)) }
        if (uid != me) {
            holder.addView(act.bigButton("Message", { act.openChat(uid, name, user) }), pad)
            holder.addView(act.bigButton("Voice Call", { act.ensureChat(uid, name, user) { cid -> act.startCall(cid, uid, name) } }, false), pad)
        } else {
            holder.addView(act.bigButton("My QR Code", { act.push(MyQrScreen(act)) }), pad)
            holder.addView(act.foot("Change your photo, name and bio in Settings."))
        }
    }
}

// ---------------------------------------------------------------- kode QR saya
class MyQrScreen(act: MainActivity) : Screen(act) {
    override fun build(): View {
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(Theme.grouped) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, 0, act.dp(40f)) }
        col.addView(act.navBar("My QR Code", "‹ Back", { act.pop() }), LinearLayout.LayoutParams(-1, -2))
        val user = Cfg.get(act, "user")
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = act.dpf(24f) }
            setPadding(act.dp(20f), act.dp(20f), act.dp(20f), act.dp(20f))
        }
        val bmp = try { Qr.make(Qr.PREFIX + user, 600) } catch (_: Throwable) { null }
        box.addView(ImageView(act).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(act.dp(260f), act.dp(260f)))
        box.addView(TextView(act).apply { text = Cfg.get(act, "name"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setPadding(0, act.dp(10f), 0, 0) })
        box.addView(TextView(act).apply { text = "@$user"; textSize = 15f; setTextColor(0xFF8E8E93.toInt()); gravity = Gravity.CENTER })
        col.addView(box, LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(24f) })
        col.addView(act.foot("Ask a friend to open Scan QR code in this app and point the camera here to add you."))
        col.addView(act.bigButton("Share link", {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Add me on Chat iOS 27: ${Qr.PREFIX}$user") }
            try { act.startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) { }
        }, false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(act.dp(20f), act.dp(8f), act.dp(20f), 0) })
        sv.addView(col)
        return sv
    }
}

// ---------------------------------------------------------------- pindai kode QR
@Suppress("DEPRECATION")
class ScanScreen(act: MainActivity) : Screen(act), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var cam: Camera? = null
    private var surfaceReady = false
    private var done = false
    private var last = 0L
    private val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
    private lateinit var sv: SurfaceView

    override fun build(): View {
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        sv = SurfaceView(act)
        sv.holder.addCallback(this)
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(act).apply {
            text = "Cancel"; textSize = 17f; setTextColor(Color.WHITE); setPadding(act.dp(18f), act.dp(18f), act.dp(18f), act.dp(18f)); setOnClickListener { act.pop() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val frame = View(act).apply { background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(act.dp(3f), Color.WHITE); cornerRadius = act.dpf(24f) } }
        root.addView(frame, FrameLayout.LayoutParams(act.dp(240f), act.dp(240f), Gravity.CENTER))
        root.addView(TextView(act).apply { text = "Point the camera at a friend's QR code"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = act.dp(110f) })
        root.addView(act.bigButton("Choose image", { act.pickImage(42) }, false), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(act.dp(40f), 0, act.dp(40f), act.dp(40f)) })
        return root
    }

    override fun onShow() {
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 41)
        else tryOpen()
    }

    override fun onPermission(req: Int, granted: Boolean) {
        if (req != 41) return
        if (granted) tryOpen() else Toast.makeText(act, "Camera permission is needed to scan. You can still choose an image.", Toast.LENGTH_LONG).show()
    }

    private fun tryOpen() {
        if (!surfaceReady || cam != null || done) return
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val c = Camera.open()
            c.setDisplayOrientation(90)
            val p = c.parameters
            val best = p.supportedPreviewSizes.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) }
            if (best != null) p.setPreviewSize(best.width, best.height)
            if (p.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            c.parameters = p
            c.setPreviewDisplay(sv.holder)
            c.setPreviewCallback(this)
            c.startPreview()
            cam = c
        } catch (_: Throwable) { Toast.makeText(act, "Could not open the camera.", Toast.LENGTH_SHORT).show() }
    }

    private fun release() {
        try { cam?.setPreviewCallback(null); cam?.stopPreview(); cam?.release() } catch (_: Throwable) { }
        cam = null
    }

    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; tryOpen() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; release() }
    override fun onHide() { release() }
    override fun onPop() { release() }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (done || data == null || camera == null) return
        val now = System.currentTimeMillis()
        if (now - last < 250) return
        last = now
        try {
            val s = camera.parameters.previewSize
            val src = PlanarYUVLuminanceSource(data, s.width, s.height, 0, 0, s.width, s.height, false)
            val r = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
            handle(r.text)
        } catch (_: NotFoundException) {
        } catch (_: Throwable) {
        } finally { reader.reset() }
    }

    private fun handle(text: String) {
        val u = Qr.parse(text)
        if (u == null) { Toast.makeText(act, "That is not a Chat iOS 27 code.", Toast.LENGTH_SHORT).show(); return }
        done = true; release(); act.pop(); act.lookupUser(u)
    }

    override fun onResult(req: Int, res: Int, data: Intent?) {
        if (req != 42 || res != Activity.RESULT_OK || data?.data == null) return
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = act.contentResolver.openInputStream(data.data!!)?.use { BitmapFactory.decodeStream(it, null, o) } ?: throw RuntimeException("x")
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val r = reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))))
            handle(r.text)
        } catch (_: Throwable) { Toast.makeText(act, "No QR code found in that image.", Toast.LENGTH_SHORT).show() } finally { reader.reset() }
    }
}
