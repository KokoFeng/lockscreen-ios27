package com.ios27.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Panggilan suara telepon-ke-telepon (WebRTC). Sinyal (penawaran, jawaban, kandidat jaringan) lewat Realtime Database.
 * Berdering hanya saat aplikasi terbuka. Di sebagian jaringan seluler perlu server relay (TURN) di Settings.
 */
object CallMgr {
    var state = "idle"            // idle, outgoing, incoming, connecting, connected
    var cid = ""
    var peer = ""
    var peerName = ""
    var incomingCall = false
    var muted = false
    var speaker = false
    var startedAt = 0L
    var listener: (() -> Unit)? = null

    private var app: Context? = null
    private var initDone = false
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var stream: Stream? = null
    private var watcher: Stream? = null
    private var answered = false
    private var remoteSet = false
    private val seenIce = HashSet<String>()
    private val pendingIce = ArrayList<IceCandidate>()
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val timeout = Runnable { val c = app; if (state == "outgoing" && c != null) finish(c, "missed", true) }

    private fun notifyUi() { Net.ui { listener?.invoke() } }

    private fun constraints(): MediaConstraints =
        MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }

    // ---------------------------------------------------------------- panggilan masuk
    fun watch(act: MainActivity) {
        watcher?.close()
        val me = Cfg.get(act, "uid")
        watcher = Stream(act, "/userCalls/$me", "") { tree ->
            if (tree != null && state == "idle") {
                val ks = tree.keys()
                if (ks.hasNext()) {
                    val k = ks.next(); val o = tree.optJSONObject(k)
                    if (o != null) incoming(act, k, o.optString("from"), o.optString("name"), o.optLong("ts"))
                }
            }
        }.also { it.start() }
    }

    fun stopWatch() { watcher?.close(); watcher = null }

    private fun incoming(act: MainActivity, c: String, from: String, name: String, ts: Long) {
        Net.bg {
            val o = Db.get(act, "/calls/$c") as? JSONObject
            val fresh = ts == 0L || Math.abs(System.currentTimeMillis() - ts) < 90000L
            val ok = fresh && o != null && o.optString("state") == "ringing" && o.optString("offer").isNotEmpty()
            Net.ui {
                if (ok && state == "idle") {
                    app = act.applicationContext
                    cid = c; peer = from; peerName = name.ifEmpty { "Unknown" }; incomingCall = true
                    muted = false; speaker = false; startedAt = 0L; answered = false; remoteSet = false; seenIce.clear(); pendingIce.clear()
                    state = "incoming"
                    startRing(act)
                    act.push(CallScreen(act))
                }
            }
        }
    }

    // ---------------------------------------------------------------- memulai panggilan
    fun startOutgoing(ctx: Context, chatId: String, peerUid: String, name: String) {
        if (state != "idle") return
        app = ctx.applicationContext
        cid = chatId; peer = peerUid; peerName = name; incomingCall = false; muted = false; speaker = false
        startedAt = 0L; answered = false; remoteSet = false; seenIce.clear(); pendingIce.clear()
        state = "outgoing"
        audioMode(true)
        val me = Cfg.get(ctx, "uid")
        Net.bg {
            Db.put(ctx, "/calls/$chatId", JSONObject().put("from", me).put("to", peerUid).put("state", "ringing").toString())
            createPc(ctx)
            val p = pc
            if (p == null) { Net.ui { finish(ctx, "failed", false) }; return@bg }
            p.createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription?) {
                    if (d == null) return
                    p.setLocalDescription(Sdp(), d)
                    Net.bg {
                        Db.put(ctx, "/calls/$chatId/offer", JSONObject.quote(d.description))
                        val entry = JSONObject().put("from", me).put("name", Cfg.get(ctx, "name")).put("ts", JSONObject().put(".sv", "timestamp"))
                        Db.patch(ctx, "/", JSONObject().put("userCalls/$peerUid/$chatId", entry).toString())
                        listenCall(ctx)
                        handler.postDelayed(timeout, 45000)
                    }
                }
                override fun onCreateFailure(e: String?) { Net.ui { finish(ctx, "failed", false) } }
            }, constraints())
        }
        notifyUi()
    }

    // ---------------------------------------------------------------- menerima
    fun accept(ctx: Context) {
        if (state != "incoming") return
        state = "connecting"
        stopRing(); audioMode(true); notifyUi()
        val c = cid
        Net.bg {
            val o = Db.get(ctx, "/calls/$c") as? JSONObject
            val offer = o?.optString("offer") ?: ""
            if (offer.isEmpty()) { Net.ui { finish(ctx, "failed", false) }; return@bg }
            createPc(ctx)
            val p = pc
            if (p == null) { Net.ui { finish(ctx, "failed", false) }; return@bg }
            p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    remoteSet = true; flushIce()
                    p.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d == null) return
                            p.setLocalDescription(Sdp(), d)
                            Net.bg {
                                Db.patch(ctx, "/calls/$c", JSONObject().put("answer", d.description).put("state", "accepted").toString())
                                listenCall(ctx)
                            }
                        }
                    }, constraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))
        }
    }

    fun decline(ctx: Context) {
        if (state != "incoming") return
        val c = cid
        Net.bg { Db.put(ctx, "/calls/$c/state", "\"declined\"") }
        finish(ctx, "declined", false)
    }

    fun hangUp(ctx: Context) { finish(ctx, if (startedAt > 0L) "ended" else "missed", true) }

    fun setMute(on: Boolean) { muted = on; try { track?.setEnabled(!on) } catch (_: Throwable) { } }

    fun setSpeaker(on: Boolean) {
        speaker = on
        try { (app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isSpeakerphoneOn = on } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- bagian dalam
    private fun createPc(ctx: Context) {
        if (!initDone) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initDone = true
        }
        val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = f
        val src = f.createAudioSource(MediaConstraints())
        source = src
        val tr = f.createAudioTrack("a0", src)
        track = tr
        val ice = ArrayList<PeerConnection.IceServer>()
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val tu = Cfg.get(ctx, "turn_url")
        if (tu.isNotBlank()) ice.add(PeerConnection.IceServer.builder(tu).setUsername(Cfg.get(ctx, "turn_user")).setPassword(Cfg.get(ctx, "turn_pass")).createIceServer())
        val cfg = PeerConnection.RTCConfiguration(ice)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val me = Cfg.get(ctx, "uid"); val c = cid
        val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceCandidate(cand: IceCandidate?) {
                if (cand == null) return
                val key = System.currentTimeMillis().toString() + (Math.random() * 100000).toInt()
                val j = JSONObject().put("m", cand.sdpMid ?: "").put("i", cand.sdpMLineIndex).put("c", cand.sdp)
                Net.bg { Db.put(ctx, "/calls/$c/ice/$me/$key", j.toString()) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) {
                    Net.ui {
                        if (state != "connected" && state != "idle") {
                            state = "connected"; startedAt = System.currentTimeMillis(); handler.removeCallbacks(timeout); notifyUi()
                        }
                    }
                } else if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) {
                    Net.ui { finish(ctx, "failed", true) }
                }
            }
        })
        pc = p
        if (p != null) p.addTrack(tr, listOf("s0"))
    }

    private fun flushIce() {
        val p = pc ?: return
        val list = ArrayList(pendingIce); pendingIce.clear()
        for (c in list) p.addIceCandidate(c)
    }

    private fun listenCall(ctx: Context) {
        stream?.close()
        val c = cid; val other = peer
        stream = Stream(ctx, "/calls/$c", "") { tree ->
            if (tree != null) {
                val st = tree.optString("state")
                if (!incomingCall && !answered) {
                    val ans = tree.optString("answer")
                    if (ans.isNotEmpty()) {
                        answered = true
                        pc?.setRemoteDescription(object : Sdp() { override fun onSetSuccess() { remoteSet = true; flushIce() } },
                            SessionDescription(SessionDescription.Type.ANSWER, ans))
                    }
                }
                val ice = tree.optJSONObject("ice")?.optJSONObject(other)
                if (ice != null) {
                    val ks = ice.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        if (seenIce.add(k)) {
                            val o = ice.optJSONObject(k)
                            if (o != null) {
                                val cand = IceCandidate(o.optString("m"), o.optInt("i"), o.optString("c"))
                                if (remoteSet) pc?.addIceCandidate(cand) else pendingIce.add(cand)
                            }
                        }
                    }
                }
                if (st == "declined") finish(ctx, "declined", false)
                else if (st == "ended") finish(ctx, "ended", false)
            }
        }.also { it.start() }
    }

    private fun finish(ctx: Context, why: String, notifyPeer: Boolean) {
        if (state == "idle") return
        val dur = if (startedAt > 0L) (System.currentTimeMillis() - startedAt) / 1000 else 0L
        val c = cid; val p = peer; val wasCaller = !incomingCall
        val me = Cfg.get(ctx, "uid")
        stream?.close(); stream = null
        handler.removeCallbacks(timeout)
        stopRing()
        try { pc?.close() } catch (_: Throwable) { }
        try { track?.dispose() } catch (_: Throwable) { }
        try { source?.dispose() } catch (_: Throwable) { }
        try { factory?.dispose() } catch (_: Throwable) { }
        pc = null; track = null; source = null; factory = null
        audioMode(false)
        state = "idle"
        Net.bg {
            if (notifyPeer) Db.put(ctx, "/calls/$c/state", "\"ended\"")
            Db.patch(ctx, "/", JSONObject().put("userCalls/$me/$c", JSONObject.NULL).put("userCalls/$p/$c", JSONObject.NULL).toString())
            if (wasCaller) {
                val text = when {
                    dur > 0 -> "📞 Voice call · " + (dur / 60) + ":" + "%02d".format(dur % 60)
                    why == "declined" -> "📞 Call declined"
                    else -> "📞 Missed call"
                }
                postLog(ctx, c, p, me, text)
            }
        }
        notifyUi()
    }

    private fun postLog(ctx: Context, c: String, p: String, me: String, text: String) {
        val key = "%013d".format(System.currentTimeMillis()) + "zz"
        val sv = JSONObject().put(".sv", "timestamp")
        val up = JSONObject()
        up.put("chats/$c/messages/$key", JSONObject().put("from", me).put("text", text).put("ts", sv))
        for (u in listOf(me, p)) { up.put("userChats/$u/$c/lastText", text); up.put("userChats/$u/$c/lastFrom", me); up.put("userChats/$u/$c/lastTs", sv) }
        Db.patch(ctx, "/", up.toString())
    }

    private fun audioMode(on: Boolean) {
        val a = app ?: return
        try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            if (!on) am.isSpeakerphoneOn = false
        } catch (_: Throwable) { }
    }

    private fun startRing(ctx: Context) {
        try {
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r.play(); ringtone = r
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (_: Throwable) { }
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (_: Throwable) { }
        ringtone = null
        try { (app?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (_: Throwable) { }
    }

    fun shutdown(ctx: Context) { if (state != "idle") finish(ctx, "ended", true); stopWatch() }
}

class CallScreen(act: MainActivity) : Screen(act) {
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { updateStatus(); handler.postDelayed(this, 1000) } }

    override fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2C2C2E.toInt(), 0xFF000000.toInt()))
            setPadding(act.dp(24f), act.dp(90f), act.dp(24f), act.dp(60f))
        }
        val p = Users.cached(CallMgr.peer)
        root.addView(AvatarView(act, 130f).apply { name = CallMgr.peerName; bmp = b64ToBitmap(p?.optString("avatar")) }, LinearLayout.LayoutParams(act.dp(130f), act.dp(130f)))
        root.addView(TextView(act).apply { text = CallMgr.peerName; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, act.dp(18f), 0, 0) })
        status = TextView(act).apply { textSize = 17f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER; setPadding(0, act.dp(6f), 0, 0) }
        root.addView(status)
        root.addView(View(act), LinearLayout.LayoutParams(-1, 0, 1f))
        buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun circle(label: String, caption: String, color: Int, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(act.dp(16f), 0, act.dp(16f), 0) }
        box.addView(TextView(act).apply {
            text = label; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }; setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(act.dp(68f), act.dp(68f)))
        box.addView(TextView(act).apply { text = caption; textSize = 13f; setTextColor(0xFFCCCCCC.toInt()); gravity = Gravity.CENTER; setPadding(0, act.dp(6f), 0, 0) })
        return box
    }

    private fun updateStatus() {
        status.text = when (CallMgr.state) {
            "outgoing" -> "Calling…"
            "incoming" -> "Incoming voice call"
            "connecting" -> "Connecting…"
            "connected" -> { val s = (System.currentTimeMillis() - CallMgr.startedAt) / 1000; "%d:%02d".format(s / 60, s % 60) }
            else -> "Call ended"
        }
    }

    private fun refresh() {
        if (CallMgr.state == "idle") { handler.removeCallbacks(tick); act.pop(); return }
        updateStatus()
        buttons.removeAllViews()
        val red = 0xFFFF3B30.toInt(); val green = 0xFF34C759.toInt(); val gray = 0x44FFFFFF
        when (CallMgr.state) {
            "incoming" -> {
                buttons.addView(circle("✕", "Decline", red) { CallMgr.decline(act) })
                buttons.addView(circle("✆", "Accept", green) { act.withMic { CallMgr.accept(act) } })
            }
            "connected" -> {
                buttons.addView(circle("🎤", if (CallMgr.muted) "Unmute" else "Mute", if (CallMgr.muted) 0xFFFFFFFF.toInt() else gray) { CallMgr.setMute(!CallMgr.muted); refresh() })
                buttons.addView(circle("✕", "End", red) { CallMgr.hangUp(act) })
                buttons.addView(circle("🔊", "Speaker", if (CallMgr.speaker) 0xFFFFFFFF.toInt() else gray) { CallMgr.setSpeaker(!CallMgr.speaker); refresh() })
            }
            else -> buttons.addView(circle("✕", "End", red) { CallMgr.hangUp(act) })
        }
    }

    override fun onShow() {
        CallMgr.listener = { refresh() }
        refresh(); handler.removeCallbacks(tick); handler.postDelayed(tick, 1000)
    }

    override fun onHide() { handler.removeCallbacks(tick) }
    override fun onPop() { handler.removeCallbacks(tick); CallMgr.listener = null }
    override fun onBack(): Boolean = true
}
