package com.mersal.app

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MersalService : AccessibilityService() {

    companion object {
        @Volatile var instance: MersalService? = null
        private val PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEND_LABELS = listOf("Send", "إرسال", "ارسال")
        private const val WAIT_LIMIT_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var cfg: SendConfig? = null
    private var running = false
    private var sent = 0          // total sent across all numbers
    private var total = 0         // numbers x count
    private var idx = 0           // current number index
    private var sentHere = 0      // sent to the current number
    private var firstPending = true // first message of a chat comes pre-filled from the link
    private var progressAt = 0L

    private val tick = Runnable { step() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SendEngine.listener?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        if (running) stopRun(SendEngine.State.STOPPED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (running) stopRun(SendEngine.State.STOPPED)
        instance = null
        SendEngine.listener?.invoke()
        return super.onUnbind(intent)
    }

    /** Volume Down = emergency stop while a run is active. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (running && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) stopRun(SendEngine.State.STOPPED)
            return true
        }
        return false
    }

    fun begin(c: SendConfig) {
        handler.removeCallbacks(tick)
        cfg = c
        sent = 0
        idx = 0
        sentHere = 0
        firstPending = true
        total = c.count * c.phones.size
        running = true
        progressAt = SystemClock.elapsedRealtime()
        SendEngine.update(SendEngine.State.RUNNING, 0, total)
        if (!launchChat(c.phones[0], c.message)) {
            stopRun(SendEngine.State.NO_WHATSAPP)
            return
        }
        handler.postDelayed(tick, 2500)
    }

    fun stopRun(s: SendEngine.State) {
        running = false
        handler.removeCallbacks(tick)
        SendEngine.update(s, sent, total)
        if (s != SendEngine.State.IDLE) bringAppToFront()
    }

    private fun bringAppToFront() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun launchChat(phone: String, message: String): Boolean {
        val pkg = PKGS.firstOrNull { isInstalled(it) } ?: return false
        val uris = listOf(
            "https://wa.me/$phone?text=${Uri.encode(message)}",
            "whatsapp://send?phone=$phone&text=${Uri.encode(message)}"
        )
        for (u in uris) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: ActivityNotFoundException) {
            }
        }
        return false
    }

    private fun step() {
        val c = cfg ?: return
        if (!running) return

        if (SystemClock.elapsedRealtime() - progressAt > WAIT_LIMIT_MS) {
            stopRun(SendEngine.State.TIMEOUT)
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in PKGS) {
            handler.postDelayed(tick, 700)
            return
        }

        val send = findSend(root)
        if (send == null) {
            // First message of a chat arrives pre-filled from the link: just wait for it to load.
            // Later messages: the box is empty, so type the text and wait for the send button.
            if (!firstPending) {
                val edit = findEdit(root)
                if (edit != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, c.message)
                    }
                    edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            handler.postDelayed(tick, 600)
            return
        }

        if (click(send)) {
            sent++
            sentHere++
            firstPending = false
            progressAt = SystemClock.elapsedRealtime()
            SendEngine.update(SendEngine.State.RUNNING, sent, total)

            if (sentHere >= c.count) {
                if (idx + 1 >= c.phones.size) {
                    stopRun(SendEngine.State.DONE)
                    return
                }
                // Move on to the next number, one after the other.
                idx++
                sentHere = 0
                firstPending = true
                if (!launchChat(c.phones[idx], c.message)) {
                    stopRun(SendEngine.State.NO_WHATSAPP)
                    return
                }
                handler.postDelayed(tick, maxOf(c.intervalMs, 2500L))
            } else {
                handler.postDelayed(tick, c.intervalMs)
            }
        } else {
            handler.postDelayed(tick, 600)
        }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (p in PKGS) {
            val hit = root.findAccessibilityNodeInfosByViewId("$p:id/send")?.firstOrNull()
            if (hit != null) return hit
        }
        for (label in SEND_LABELS) {
            val hit = root.findAccessibilityNodeInfosByText(label)?.firstOrNull {
                it.className?.toString()?.contains("EditText") != true &&
                    (it.isClickable || it.parent?.isClickable == true)
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun findEdit(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (p in PKGS) {
            val hit = root.findAccessibilityNodeInfosByViewId("$p:id/entry")?.firstOrNull { it.isEnabled }
            if (hit != null) return hit
        }
        return dfs(root) { it.className?.toString() == "android.widget.EditText" }
    }

    private fun dfs(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val r = dfs(n.getChild(i), pred)
            if (r != null) return r
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            hops++
        }
        return false
    }
}
