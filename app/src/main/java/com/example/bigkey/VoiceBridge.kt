package com.example.bigkey

/** 音声入力の結果をキーボードに渡す。入力欄に戻ったとき（onStartInputView）に取り出して入力する。 */
object VoiceBridge {
    @Volatile
    private var pending: String? = null

    fun deliver(text: String) {
        pending = text
    }

    fun consume(): String? {
        val t = pending
        pending = null
        return t
    }
}
