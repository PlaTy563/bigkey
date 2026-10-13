package com.example.bigkey

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import android.widget.inline.InlinePresentationSpec
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class BigKeyService : InputMethodService(), KeyboardView.Listener {

    private lateinit var kb: KeyboardView
    private lateinit var candScroll: HorizontalScrollView
    private lateinit var candRow: LinearLayout
    private lateinit var autoScroll: HorizontalScrollView
    private lateinit var autoRow: LinearLayout
    private lateinit var bwButton: TextView
    private lateinit var dict: Dictionary
    private val composer = Composer()

    private var japanese = true
    private var userJapanese = true // 利用者が最後に選んだ日本語/英字
    private var noLearn = false

    private var candidates: List<Dictionary.Cand> = emptyList()
    private var candidateReading = ""
    private var liveActive = false // 自動変換で、候補の先頭を入力中の文字列として表示している
    private var candIndex = -1 // -1: 変換前、0以上: 変換中（選択中の候補）

    override fun onCreate() {
        super.onCreate()
        dict = Dictionary(this)
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF161616.toInt())
        }
        candRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candRow,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(
                autoRow,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        val barFrame = FrameLayout(this).apply {
            addView(candScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(autoScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        bwButton = TextView(this).apply {
            text = "Bitwarden"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(0xFF2F4F8F.toInt())
            setOnClickListener { launchBitwarden() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(barFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(bwButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        kb = KeyboardView(this).also {
            it.listener = this
            // 日本語入力中だけ、ローマ字として続かないキーを選ばれにくくする
            it.likelyPredicate = { c -> !japanese || composer.canContinue(c) }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(
            kb,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clearState()

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val forceEnglish = cls != InputType.TYPE_CLASS_TEXT ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        noLearn = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        japanese = userJapanese && !forceEnglish
        kb.reloadSettings()
        bwButton.visibility = if (Prefs.showBitwarden(this) && bitwardenIntent() != null) View.VISIBLE else View.GONE
        kb.setJapanese(japanese)
        kb.setSymbolLayer(
            cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        clearState()
        super.onFinishInputView(finishingInput)
    }

    /** カーソルが外部要因で動いたら、入力中の状態を捨てる。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val active = !composer.isEmpty() || candIndex >= 0
        if (active && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            clearState()
            currentInputConnection?.finishComposingText()
        }
    }

    // ---- キー入力 ----

    override fun onText(key: KeyboardView.Key, shifted: Boolean) {
        val ic = currentInputConnection ?: return

        if (!japanese) {
            val s = if (key.kind == KeyboardView.Kind.LETTER && shifted) key.text.uppercase() else key.text
            ic.commitText(s, 1)
            return
        }

        if (candIndex >= 0) commitCandidateAt(ic, candIndex)

        if (key.kind == KeyboardView.Kind.LETTER) {
            if (shifted) composer.appendLiteral(key.text.uppercase()) else composer.inputLetter(key.text[0])
            syncComposition(ic)
        } else if (composer.isEmpty()) {
            ic.commitText(key.text, 1)
        } else {
            composer.appendLiteral(key.text)
            syncComposition(ic)
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        if (!japanese || (composer.isEmpty() && candIndex < 0)) {
            ic.commitText(" ", 1)
            return
        }
        if (candIndex < 0) {
            composer.flushPending()
            val reading = composer.kanaText()
            if (reading.isEmpty()) {
                clearState()
                ic.commitText(" ", 1)
                return
            }
            candidateReading = reading
            candidates = dict.candidates(reading)
            // 自動変換中はすでに先頭の候補が表示されているので、次の候補へ進める
            candIndex = if (liveActive && candidates.size > 1) 1 else 0
        } else {
            candIndex = (candIndex + 1) % candidates.size
        }
        ic.setComposingText(candidates[candIndex].text, 1)
        refreshBar()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (japanese) {
            if (candIndex >= 0) {
                // 変換を取り消して読みに戻す
                syncComposition(ic)
                return
            }
            if (!composer.isEmpty()) {
                composer.backspace()
                syncComposition(ic)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        if (japanese && (candIndex >= 0 || !composer.isEmpty())) {
            if (candIndex >= 0) {
                commitCandidateAt(ic, candIndex)
            } else {
                commitAsIs(ic)
            }
            return
        }
        val ei = currentInputEditorInfo
        val options = ei?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onToggleMode() {
        currentInputConnection?.let { commitAsIs(it) }
        japanese = !japanese
        userJapanese = japanese
        kb.setJapanese(japanese)
    }

    // ---- 入力中の文字列と変換候補 ----

    /** 入力中の文字列をエディタに反映し、変換候補を更新する。 */
    private fun syncComposition(ic: InputConnection) {
        val trailing = composer.trailingRomaji()
        val kana = composer.kanaText()
        liveActive = false
        if (Prefs.autoConvert(this) && kana.isNotEmpty()) {
            candidateReading = kana
            candidates = dict.candidates(kana)
            liveActive = candidates.isNotEmpty()
        } else {
            candidateReading = composer.previewReading()
            candidates = dict.candidates(candidateReading)
        }
        candIndex = -1
        if (composer.isEmpty()) {
            ic.commitText("", 1)
        } else if (liveActive) {
            ic.setComposingText(candidates[0].text + trailing, 1)
        } else {
            ic.setComposingText(composer.displayText(), 1)
        }
        refreshBar()
        kb.setComposing(!composer.isEmpty())
    }

    /** 入力中の文字列を変換せずそのまま確定する。 */
    private fun commitAsIs(ic: InputConnection) {
        if (candIndex >= 0) {
            commitCandidateAt(ic, candIndex)
        } else if (!composer.isEmpty()) {
            val trailing = composer.trailingRomaji()
            if (liveActive) {
                val cand = candidates[0]
                ic.commitText(cand.text + trailing, 1)
                if (!noLearn) dict.learn(candidateReading, cand)
            } else {
                composer.flushPending()
                ic.commitText(composer.kanaText(), 1)
            }
            clearState()
        }
    }

    private fun commitCandidateAt(ic: InputConnection, index: Int) {
        val cand = candidates.getOrNull(index) ?: return
        // 末尾に未確定のローマ字が残っているときは、それもそのまま確定する
        val trailing = if (liveActive && candIndex < 0) composer.trailingRomaji() else ""
        ic.commitText(cand.text + trailing, 1)
        if (!noLearn && cand.text != candidateReading) dict.learn(candidateReading, cand)
        clearState()
    }

    private fun clearState() {
        composer.clear()
        candidates = emptyList()
        candidateReading = ""
        candIndex = -1
        liveActive = false
        refreshBar()
        if (::kb.isInitialized) kb.setComposing(false)
    }

    private fun refreshBar() {
        if (!::candRow.isInitialized) return
        candRow.removeAllViews()
        updateBarVisibility()
        candidates.forEachIndexed { i, c ->
            val tv = TextView(this).apply {
                text = c.text
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(0xFFEEEEEE.toInt())
                if (i == candIndex) setBackgroundColor(0xFF4A6FC4.toInt())
                setOnClickListener {
                    currentInputConnection?.let { ic -> commitCandidateAt(ic, i) }
                }
            }
            candRow.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        if (candIndex >= 0) {
            candScroll.post { candRow.getChildAt(candIndex)?.let { candScroll.smoothScrollTo(it.left, 0) } }
        } else {
            candScroll.scrollTo(0, 0)
        }
    }

    private fun updateBarVisibility() {
        val showAuto = autoRow.childCount > 0 && candidates.isEmpty() && composer.isEmpty()
        autoScroll.visibility = if (showAuto) View.VISIBLE else View.GONE
        candScroll.visibility = if (showAuto) View.INVISIBLE else View.VISIBLE
    }

    // ---- Bitwarden ----

    private fun bitwardenIntent(): Intent? {
        for (pkg in listOf("com.x8bit.bitwarden", "com.x8bit.bitwarden.beta")) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        }
        return null
    }

    private fun launchBitwarden() {
        val i = bitwardenIntent() ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    /** 自動入力の候補（Bitwarden など）をキーボード上に出すための要求。Android 11 以上。 */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Prefs.showBitwarden(this)) return null
        return try {
            val styles = UiVersions.newStylesBuilder()
                .addStyle(InlineSuggestionUi.newStyleBuilder().build())
                .build()
            val spec = InlinePresentationSpec.Builder(Size(dp(100), dp(40)), Size(dp(400), dp(44)))
                .setStyle(styles)
                .build()
            InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(6)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !::autoRow.isInitialized) return false
        autoRow.removeAllViews()
        val list = response.inlineSuggestions
        if (list.isEmpty()) {
            updateBarVisibility()
            return false
        }
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        var pending = list.size
        for (s in list) {
            try {
                s.inflate(this, size, mainExecutor) { v ->
                    if (v != null) {
                        autoRow.addView(v)
                        updateBarVisibility()
                    }
                }
            } catch (_: Throwable) {
                pending--
            }
        }
        return pending > 0
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
