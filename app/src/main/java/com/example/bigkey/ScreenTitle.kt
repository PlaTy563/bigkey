package com.example.bigkey

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 画面上部に出ている文字（トーク相手の名前など）を読み取るための補助サービス。
 * 読むのは「場面」に登録された対象アプリの、画面の上から約25%の範囲の文字だけ。
 * 読み取った文字は端末のメモリ上に置くだけで、保存も送信もしない。
 * 設定で有効にしなければ動かない（キーワード付きの場面を使わないなら不要）。
 */
class ScreenTitle : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: ScreenTitle? = null

        @Volatile
        private var lastPkg: String? = null

        @Volatile
        private var lastTitles: List<String> = emptyList()

        fun isRunning(): Boolean = instance != null

        /** 場面の対象アプリが変わったら、読み取り範囲の設定を反映し直す。 */
        fun refreshScope() {
            instance?.applyScope()
        }

        /** 前面に来たらこのサービスを自動でオフにする決済アプリ（PayPay）。 */
        private const val PAYPAY_PACKAGE = "jp.ne.paypay.android.app"
        private const val AUTO_OFF_CHANNEL_ID = "paypay_auto_off"
        private const val AUTO_OFF_NOTIFICATION_ID = 2001

        /** 指定アプリの画面上部の文字を、今この場で読み直して返す。読めなければ直近の結果を返す。 */
        fun read(pkg: String?): List<String> {
            val svc = instance
            if (svc != null && pkg != null) {
                val now = svc.collect(pkg)
                if (!now.isNullOrEmpty()) return now
            }
            return if (lastPkg == pkg) lastTitles else emptyList()
        }
    }

    private var lastEventAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var autoOffTriggered = false

    override fun onServiceConnected() {
        instance = this
        applyScope()
    }

    /**
     * 画面を読む対象を、場面に登録されたアプリだけに絞る。
     * ただし PayPay だけは検知専用に対象へ加える：PayPay は有効なユーザー補助
     * サービス自体を制限対象にするため（packageNames の絞り込みでは回避できない
     * ことを実機で確認済み）、前面に来たらこのサービス自身をオフにして回避する。
     * PayPay の中身は読まない（パッケージ名の一致だけを見る）。
     * 対象が空のときは自アプリだけを指定する（空配列は「全アプリ」の意味になるため）。
     */
    fun applyScope() {
        try {
            val watched = SceneStore.instance(this).watchedPackages()
            val info = serviceInfo ?: return
            val targets = watched + PAYPAY_PACKAGE
            info.packageNames =
                if (targets.isEmpty()) arrayOf(packageName) else targets.toTypedArray()
            serviceInfo = info
        } catch (_: Exception) {
        }
    }

    /**
     * PayPay の前面を検知したときの自動OFF。通知を出してから少し遅らせて
     * disableSelf() する。オフになれば PayPay 側の制限は自動で解除される。
     * 戻すのは Android の仕様上ユーザーの手動操作が必要。
     */
    private fun onPayPayForeground() {
        if (autoOffTriggered) return
        autoOffTriggered = true
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        AUTO_OFF_CHANNEL_ID,
                        "PayPay自動OFFのお知らせ",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, AUTO_OFF_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("BigKey 見出し読み取りをオフにしました")
                .setContentText("PayPayを開いたため、ユーザー補助を自動でオフにしました。使い終わったらタップしてオンに戻してください。")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(AUTO_OFF_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // 通知が出せなくても自動OFF自体は続行する
        }
        handler.postDelayed({ disableSelf() }, 500)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // PayPay が前面に来たら、自分をオフにして利用制限を回避する
        if (pkg == PAYPAY_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            onPayPayForeground()
            return
        }
        if (pkg !in SceneStore.instance(this).watchedPackages()) return
        val now = System.currentTimeMillis()
        if (now - lastEventAt < 300) return
        lastEventAt = now
        collect(pkg)
    }

    /** 対象アプリの画面を探す。キーボードが出ていても、アプリ側の画面を選ぶ。 */
    private fun findRoot(pkg: String): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        } catch (_: Exception) {
        }
        return try {
            rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
        } catch (_: Exception) {
            null
        }
    }

    /** 対象アプリの画面上部の文字を集める。対象アプリが見つからなければ null。 */
    private fun collect(pkg: String): List<String>? {
        if (pkg !in SceneStore.instance(this).watchedPackages()) return null
        val root = findRoot(pkg) ?: return null
        val limit = (resources.displayMetrics.heightPixels * 0.25f).toInt()
        val out = LinkedHashSet<String>()
        var count = 0
        val rect = Rect()
        fun add(s: CharSequence?) {
            val t = s?.toString()?.trim() ?: return
            if (t.isNotEmpty() && t.length <= 100) out.add(t)
        }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || count > 600) return
            count++
            n.getBoundsInScreen(rect)
            if (rect.top < limit && rect.bottom > 0) {
                add(n.text)
                add(n.contentDescription)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val list = out.toList()
        lastPkg = pkg
        lastTitles = list
        return list
    }
}
