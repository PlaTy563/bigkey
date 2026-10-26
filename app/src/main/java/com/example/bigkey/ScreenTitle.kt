package com.example.bigkey

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 画面上部に出ている文字（トーク相手の名前など）を読み取るための補助サービス。
 * 読むのは「場面」に登録された対象アプリの、画面の上から約15%の範囲の文字だけ。
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

        /** 指定アプリの画面上部の文字を、今この場で読み直して返す。読めなければ直近の結果を返す。 */
        fun read(pkg: String?): List<String> {
            val svc = instance
            if (svc != null && pkg != null) {
                val now = svc.collect(pkg)
                if (now != null) return now
            }
            return if (lastPkg == pkg) lastTitles else emptyList()
        }
    }

    private var lastEventAt = 0L

    override fun onServiceConnected() {
        instance = this
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
        if (pkg !in SceneStore.instance(this).watchedPackages()) return
        val now = System.currentTimeMillis()
        if (now - lastEventAt < 300) return
        lastEventAt = now
        collect(pkg)
    }

    /** 対象アプリが前面にあるときだけ読む。それ以外は null。 */
    private fun collect(pkg: String): List<String>? {
        if (pkg !in SceneStore.instance(this).watchedPackages()) return null
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        if (root.packageName?.toString() != pkg) return null
        val limit = (resources.displayMetrics.heightPixels * 0.15f).toInt()
        val out = LinkedHashSet<String>()
        var count = 0
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || count > 400) return
            count++
            n.getBoundsInScreen(rect)
            if (rect.top < limit) {
                n.text?.toString()?.trim()?.let { if (it.isNotEmpty() && it.length <= 100) out.add(it) }
                n.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty() && it.length <= 100) out.add(it) }
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
