package com.example.bigkey

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.ScrollView
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
    private lateinit var bwButton: View
    private lateinit var editButton: View
    private lateinit var overlay: PreviewOverlay
    private val candViews = ArrayList<View>()
    private lateinit var toolRow: LinearLayout
    private val toolViews = HashMap<String, View>()
    private lateinit var selectButton: IconButton
    private lateinit var expandButton: IconButton
    private lateinit var candView: LinearLayout
    private lateinit var candGrid: LinearLayout
    private lateinit var enDict: EnglishDict
    private var selectMode = false // オンのあいだ、カーソルの矢印で範囲を選ぶ
    private var enWord = "" // 英字入力中の、打ちかけの単語
    private var enSug: List<String> = emptyList()
    private var suggestEn = false
    private var undoInfo: UndoInfo? = null

    /** 確定した変換を取り消して、読みに戻すための情報。 */
    private class UndoInfo(val reading: String, val committed: String, val cand: Dictionary.Cand?, val trailing: String)
    private var lastCommitted = "" // 直前に確定した文字列（次の語の予測に使う）
    private var suggestions: List<String> = emptyList()
    private lateinit var panel: LinearLayout
    private lateinit var editView: LinearLayout
    private lateinit var emojiView: LinearLayout
    private lateinit var clipList: LinearLayout
    private var inputPkg: String? = null
    private var sceneChoice: String? = null // null=自動で選ぶ
    private lateinit var clipStore: ClipStore
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { recordClip() }
    private lateinit var dict: Dictionary
    private val composer = Composer()

    private var japanese = true
    private var userJapanese = true // 利用者が最後に選んだ日本語/英字
    private var noLearn = false

    private var candidates: List<Dictionary.Cand> = emptyList()
    private var candidateReading = ""
    private var liveShown = "" // 自動変換で入力中の文字列として表示している内容（かな部分のみ）
    private var liveActive = false // 自動変換で、候補の先頭を入力中の文字列として表示している
    private var candIndex = -1 // -1: 変換前、0以上: 変換中（選択中の候補）

    override fun onCreate() {
        super.onCreate()
        dict = Dictionary(this)
        enDict = EnglishDict(this)
        clipStore = ClipStore.instance(this)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboard?.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            clipboard?.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        clipStore.clearHistory()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.of(this@BigKeyService).bg)
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
        bwButton = IconButton(this, IconButton.Icon.KEY, "Bitwarden").apply { setOnClickListener { launchBitwarden() } }
        editButton = IconButton(this, IconButton.Icon.CLIP, "クリップボード").apply { setOnClickListener { openPanel(false) } }
        val leftButton = IconButton(this, IconButton.Icon.LEFT, "カーソルを左へ").apply { repeatAction = { onCursor(-1) } }
        val rightButton = IconButton(this, IconButton.Icon.RIGHT, "カーソルを右へ").apply { repeatAction = { onCursor(1) } }
        val emojiButton = IconButton(this, IconButton.Icon.EMOJI, "絵文字").apply { setOnClickListener { openPanel(true) } }
        val voiceButton = IconButton(this, IconButton.Icon.MIC, "音声入力").apply { setOnClickListener { startVoice() } }
        val settingsButton = IconButton(this, IconButton.Icon.GEAR, "設定").apply { setOnClickListener { openSettings() } }
        selectButton = IconButton(this, IconButton.Icon.SELECT, "範囲選択").apply {
            setOnClickListener { setSelectMode(!selectMode) }
        }
        toolViews["bw"] = bwButton
        toolViews["clip"] = editButton
        toolViews["emoji"] = emojiButton
        toolViews["mic"] = voiceButton
        toolViews["settings"] = settingsButton
        toolViews["select"] = selectButton
        toolViews["left"] = leftButton
        toolViews["right"] = rightButton
        toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.of(this@BigKeyService).bar)
        }
        layoutToolbar()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.of(this@BigKeyService).bar)
            addView(barFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            expandButton = IconButton(this@BigKeyService, IconButton.Icon.DOWN, "候補をすべて表示").apply {
                visibility = View.GONE
                setOnClickListener { openCandList() }
            }
            addView(
                expandButton,
                LinearLayout.LayoutParams(dp(48), dp(34)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(dp(4), 0, dp(6), 0)
                }
            )
        }
        kb = KeyboardView(this).also {
            it.listener = this
            // 日本語入力中だけ、ローマ字として続かないキーを選ばれにくくする
            it.likelyPredicate = { c -> !japanese || composer.canContinue(c) }
        }
        root.addView(toolRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        panel = buildPanel()
        val body = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        overlay = PreviewOverlay(this)
        kb.previewCallback = { label, l, t, r, b ->
            if (label == null) {
                overlay.show(null, 0f, 0f, 0f, 0f)
            } else {
                val a = IntArray(2)
                val o = IntArray(2)
                kb.getLocationInWindow(a)
                overlay.getLocationInWindow(o)
                val dx = (a[0] - o[0]).toFloat()
                val dy = (a[1] - o[1]).toFloat()
                overlay.show(label, l + dx, t + dy, r + dx, b + dy)
            }
        }
        return FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(this@BigKeyService.overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (this@BigKeyService.overlay.fixedHeight != h) {
                    this@BigKeyService.overlay.fixedHeight = h
                    this@BigKeyService.overlay.requestLayout()
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clearState()
        inputPkg = info.packageName
        sceneChoice = null
        if (::panel.isInitialized) closePanel()
        resetSuggest()
        enWord = ""
        enSug = emptyList()
        undoInfo = null
        setSelectMode(false)
        VoiceBridge.consume()?.let { currentInputConnection?.commitText(it, 1) }

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
        suggestEn = cls == InputType.TYPE_CLASS_TEXT && !forceEnglish &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        kb.reloadSettings()
        layoutToolbar()
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
        if (enWord.isNotEmpty()) {
            val before = currentInputConnection?.getTextBeforeCursor(enWord.length, 0)?.toString()
            if (before != enWord) {
                enWord = ""
                enSug = emptyList()
                refreshBar()
            }
        }
        val active = !composer.isEmpty() || candIndex >= 0
        if (active && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            clearState()
            currentInputConnection?.finishComposingText()
        }
    }

    // ---- キー入力 ----

    override fun onText(key: KeyboardView.Key, shifted: Boolean) {
        val ic = currentInputConnection ?: return
        if (selectMode) setSelectMode(false)

        if (!japanese) {
            val s = if (key.kind == KeyboardView.Kind.LETTER && shifted) key.text.uppercase() else key.text
            ic.commitText(s, 1)
            resetSuggest()
            enWord = if (key.kind == KeyboardView.Kind.LETTER) enWord + s else ""
            updateEnglish()
            return
        }

        if (candIndex >= 0) commitCandidateAt(ic, candIndex)

        if (key.kind == KeyboardView.Kind.LETTER) {
            if (shifted) {
                // 大文字から始まる英字の連続入力（例: Apple）。続く英字はかなに変換しない
                composer.startAlphaRun(key.text.uppercase())
            } else if (composer.alphaRun) {
                composer.appendLiteral(key.text.lowercase())
            } else {
                composer.inputLetter(key.text[0])
            }
            syncComposition(ic)
        } else if (composer.isEmpty()) {
            ic.commitText(key.text, 1)
            resetSuggest()
        } else {
            composer.endAlphaRun()
            composer.appendLiteral(key.text)
            syncComposition(ic)
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        if (!japanese || (composer.isEmpty() && candIndex < 0)) {
            ic.commitText(" ", 1)
            if (enWord.isNotEmpty()) {
                enWord = ""
                updateEnglish()
            }
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
            candIndex = if (liveActive && candidates.size > 1 && candidates[0].text == liveShown) 1 else 0
        } else {
            candIndex = (candIndex + 1) % candidates.size
        }
        ic.setComposingText(candidates[candIndex].text, 1)
        refreshBar()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (selectMode) setSelectMode(false)
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
        resetSuggest()
        if (enWord.isNotEmpty()) {
            enWord = enWord.dropLast(1)
            updateEnglish()
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** 削除キーを左になぞったとき: カーソルの手前の1語（同じ種類の文字の連なり）を消す。 */
    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        if (japanese && (candIndex >= 0 || !composer.isEmpty())) {
            // 入力中の文字は、すべて消す
            composer.clear()
            candidates = emptyList()
            candIndex = -1
            liveActive = false
            ic.setComposingText("", 1)
            ic.finishComposingText()
            clearState()
            return
        }
        resetSuggest()
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: ""
        if (before.isEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        ic.deleteSurroundingText(wordLengthBefore(before), 0)
        enWord = ""
        updateEnglish()
    }

    private fun charClass(cp: Int): Int = when {
        Character.isWhitespace(cp) -> 0
        cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'.code) -> 1
        cp in 0x3040..0x309F -> 2 // ひらがな
        cp in 0x30A0..0x30FF -> 3 // カタカナ（ー を含む）
        Character.isLetterOrDigit(cp) -> 4 // 漢字など
        else -> 5 // 記号
    }

    /** text の末尾から数えて、消す1語ぶんの長さ（UTF-16 の単位）。 */
    private fun wordLengthBefore(text: String): Int {
        var i = text.length
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (charClass(cp) != 0) break
            i -= Character.charCount(cp)
        }
        if (i == 0) return text.length
        val cls = charClass(Character.codePointBefore(text, i))
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (charClass(cp) != cls) break
            i -= Character.charCount(cp)
        }
        return text.length - i
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
            resetSuggest()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onToggleMode() {
        currentInputConnection?.let { commitAsIs(it) }
        enWord = ""
        enSug = emptyList()
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
            liveShown = dict.liveText(kana)
            liveActive = candidates.isNotEmpty()
        } else {
            candidateReading = composer.previewReading()
            candidates = dict.candidates(candidateReading)
        }
        candIndex = -1
        if (composer.isEmpty()) {
            ic.commitText("", 1)
        } else if (liveActive) {
            ic.setComposingText(liveShown + trailing, 1)
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
            val committed: String
            if (liveActive) {
                committed = liveShown + trailing
                ic.commitText(committed, 1)
            } else {
                composer.flushPending()
                committed = composer.kanaText()
                ic.commitText(committed, 1)
            }
            val reading = candidateReading
            val liveConverted = liveActive && committed != composer.kanaText() + trailing
            clearState()
            afterCommit(committed, if (liveConverted) UndoInfo(reading, committed, null, trailing) else null)
        }
    }

    private fun commitCandidateAt(ic: InputConnection, index: Int) {
        val cand = candidates.getOrNull(index) ?: return
        // 末尾に未確定のローマ字が残っているときは、それもそのまま確定する
        val trailing = if (liveActive && candIndex < 0) composer.trailingRomaji() else ""
        ic.commitText(cand.text + trailing, 1)
        val reading = candidateReading
        if (!noLearn && cand.text != reading) dict.learn(reading, cand)
        if (cand.isEmoji) addRecentEmoji(cand.text)
        clearState()
        afterCommit(
            cand.text + trailing,
            if (!cand.isEmoji) UndoInfo(reading, cand.text + trailing, cand, trailing) else null
        )
    }

    /** 確定した変換を取り消して読みに戻し、次の候補を表示する。 */
    private fun undoCommit() {
        val u = undoInfo ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(u.committed.length, 0)?.toString()
        if (before != u.committed) {
            undoInfo = null
            refreshBar()
            android.widget.Toast.makeText(this, "カーソルが動いたので戻せません", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        ic.deleteSurroundingText(u.committed.length, 0)
        if (!noLearn && u.cand != null && u.cand.text != u.reading) dict.unlearn(u.reading, u.cand)
        undoInfo = null
        lastCommitted = ""
        suggestions = emptyList()
        composer.clear()
        composer.appendLiteral(u.reading)
        if (u.trailing.isNotEmpty()) composer.appendLiteral(u.trailing)
        candidateReading = u.reading
        candidates = dict.candidates(u.reading)
        liveActive = false
        val same = candidates.indexOfFirst { it.text + u.trailing == u.committed }
        candIndex = if (candidates.isEmpty()) -1 else if (same >= 0 && candidates.size > 1) (same + 1) % candidates.size else 0
        if (candIndex >= 0) {
            ic.setComposingText(candidates[candIndex].text + u.trailing, 1)
        } else {
            ic.setComposingText(composer.displayText(), 1)
        }
        refreshBar()
        kb.setComposing(true)
    }

    /** 確定したあとの処理: 次に続きそうな言葉を探し、並びを覚える。 */
    private fun afterCommit(text: String, undo: UndoInfo? = null) {
        undoInfo = undo
        if (!noLearn && lastCommitted.isNotEmpty()) dict.recordNext(lastCommitted, text)
        lastCommitted = text
        suggestions = dict.nextSuggestions(text)
        refreshBar()
    }

    private fun resetSuggest() {
        if (lastCommitted.isEmpty() && suggestions.isEmpty() && undoInfo == null) return
        undoInfo = null
        lastCommitted = ""
        suggestions = emptyList()
        refreshBar()
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
        candViews.clear()
        updateBarVisibility()
        val pal = Theme.of(this)
        if (candidates.isEmpty() && composer.isEmpty()) {
            undoInfo?.let { addChip("↩ 戻す", pal, true) { undoCommit() } }
            if (!japanese) {
                for (w in enSug) addChip(w, pal, false) { pickEnglish(w) }
            } else {
                for (sug in suggestions) {
                    addChip(sug, pal, false) {
                        val ic = currentInputConnection ?: return@addChip
                        ic.commitText(sug, 1)
                        afterCommit(sug)
                    }
                }
            }
        }
        candidates.forEachIndexed { i, c ->
            if (i > 0 && i - 1 != candIndex && i != candIndex) {
                candRow.addView(
                    View(this).apply { setBackgroundColor(pal.divider) },
                    LinearLayout.LayoutParams(dp(1), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL }
                )
            }
            val tv = TextView(this).apply {
                text = c.text
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                if (i == candIndex) {
                    setTextColor(pal.accentText)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(pal.accent)
                        cornerRadius = dp(18).toFloat()
                    }
                } else {
                    setTextColor(pal.text)
                }
                setOnClickListener {
                    currentInputConnection?.let { ic -> commitCandidateAt(ic, i) }
                }
                setOnLongClickListener {
                    // 長押しで、この候補を今後出さないようにする
                    if (c.text != candidateReading) {
                        dict.hide(candidateReading, c)
                        val ic = currentInputConnection
                        if (ic != null && liveActive) {
                            candidates = dict.candidates(candidateReading)
                            liveShown = dict.liveText(candidateReading)
                            ic.setComposingText(liveShown + composer.trailingRomaji(), 1)
                        } else {
                            candidates = dict.candidates(candidateReading)
                        }
                        candIndex = if (candIndex >= 0) 0 else -1
                        refreshBar()
                        android.widget.Toast.makeText(this@BigKeyService, "「${c.text}」を候補から外しました", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            candViews.add(tv)
            candRow.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(0, dp(4), 0, dp(4))
                }
            )
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = if (candidates.size > 1) View.VISIBLE else View.GONE
        }
        if (candIndex >= 0) {
            candScroll.post { candViews.getOrNull(candIndex)?.let { candScroll.smoothScrollTo(it.left, 0) } }
        } else {
            candScroll.scrollTo(0, 0)
        }
    }

    private fun addChip(label: String, pal: Palette, accent: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if (accent) pal.accentText else pal.text)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (accent) pal.accent else pal.special)
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { onClick() }
        }
        candRow.addView(
            tv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        )
    }

    // ---- 英単語の候補 ----

    private fun updateEnglish() {
        enSug = if (!japanese && suggestEn && enWord.length >= 2) enDict.complete(enWord, 6) else emptyList()
        refreshBar()
    }

    private fun pickEnglish(w: String) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(enWord.length, 0)
        ic.commitText("$w ", 1)
        enWord = ""
        enSug = emptyList()
        refreshBar()
    }

    // ---- ツールバー ----

    /** 設定の並び順と表示に従って、ツールバーのボタンを並べ直す。 */
    private fun layoutToolbar() {
        if (!::toolRow.isInitialized) return
        toolRow.removeAllViews()
        val hidden = Prefs.toolbarHidden(this)
        for (id in Prefs.toolbarOrder(this)) {
            if (id == "spacer") {
                toolRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                continue
            }
            if (id in hidden) continue
            val v = toolViews[id] ?: continue
            val wide = id == "left" || id == "right" || id == "select"
            toolRow.addView(
                v,
                LinearLayout.LayoutParams(dp(if (wide) 64 else 46), dp(36)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(dp(4), 0, dp(4), 0)
                }
            )
        }
    }

    private fun setSelectMode(on: Boolean) {
        selectMode = on
        if (::selectButton.isInitialized) selectButton.active = on
    }

    // ---- 候補の一覧 ----

    private fun openCandList() {
        if (candidates.size < 2) return
        val pal = Theme.of(this)
        candGrid.removeAllViews()
        val cols = 3
        var row: LinearLayout? = null
        for ((i, c) in candidates.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                candGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            }
            row?.addView(
                TextView(this).apply {
                    text = c.text
                    textSize = 18f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(pal.text)
                    setPadding(dp(6), 0, dp(6), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(pal.special)
                        cornerRadius = dp(12).toFloat()
                    }
                    setOnClickListener {
                        closePanel()
                        currentInputConnection?.let { ic -> commitCandidateAt(ic, i) }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            )
        }
        // 最後の段の空きを埋めて、幅をそろえる
        val rest = (cols - candidates.size % cols) % cols
        repeat(rest) {
            row?.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        editView.visibility = View.GONE
        emojiView.visibility = View.GONE
        candView.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
    }

    // ---- カーソル移動 ----

    override fun onCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        if (japanese && (candIndex >= 0 || !composer.isEmpty())) commitAsIs(ic)
        resetSuggest()
        enWord = ""
        enSug = emptyList()
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        if (selectMode) {
            // Shift を押しながら矢印を押したことにして、範囲を広げる
            val t = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_SHIFT_ON))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_SHIFT_ON))
        } else {
            sendDownUpKeyEvents(code)
        }
    }

    // ---- 編集パネル（カーソル移動・コピー・貼り付け・クリップボード履歴） ----

    private fun recordClip() {
        if (!Prefs.clipRecord(this)) return
        val cm = clipboard ?: return
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            // 機密扱いの内容（パスワードマネージャーのコピーなど）は記録しない
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            clipStore.add(text)
            if (::panel.isInitialized && panel.visibility == View.VISIBLE) refreshClipList()
        } catch (_: Exception) {
        }
    }

    private fun buildPanel(): LinearLayout {
        val pal = Theme.of(this)
        fun tool(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(pal.text)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pal.special)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val items = listOf<Pair<String, () -> Unit>>(
                "閉じる" to { closePanel() },
                "全選択" to { editAction(android.R.id.selectAll) },
                "コピー" to { editAction(android.R.id.copy) },
                "切取" to { editAction(android.R.id.cut) },
                "貼付" to { editAction(android.R.id.paste) },
            )
            for ((label, action) in items) {
                addView(
                    tool(label, action),
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
                )
            }
        }
        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(clipList) }
        editView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        emojiView = buildEmojiView(pal)
        candGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(
                tool("閉じる") { closePanel() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            )
            addView(ScrollView(context).apply { addView(candGrid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            visibility = View.GONE
            addView(editView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emojiView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(candView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // ---- 絵文字 ----

    private val emojiCategories: List<Pair<String, String>> = listOf(
        "顔" to "😀😃😄😁😆😅😂🤣😊😇🙂😉😍🥰😘😋😛😜🤪🤔🤨😐😑😶🙄😏😣😥😮😪😴😌😎🤓😕😟🙁😲😳🥺😢😭😱😖😞😓😩😫😤😡😠🥵🥶😷🤒🤕🤢🤮🥳🤩🫡🫠",
        "手" to "👍👎👌✌️🤞🤟🤘👈👉👆👇☝️✋🖐️👋🤙💪🙏🤝👏🙌👐🤲✍️💅🙋🙇🤦🤷",
        "ハート" to "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝✨⭐🌟💫🔥💯💢💥💦💤🎉🎊🎁🏆",
        "動物" to "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐦🐤🦆🦉🐴🦄🐝🐛🦋🐌🐞🐢🐍🐙🐠🐟🐬🐳🐋",
        "植物" to "🌱🌿☘️🍀🌾🌻🌼🌷🌹🌸🌺🍁🍂🍃🌲🌳🌴🌵🍄🌍☀️🌤️⛅☁️🌧️⛈️❄️🌈🌙",
        "食べ物" to "🍚🍙🍘🍜🍣🍱🍛🍞🥐🍳🥓🍔🍟🍕🌭🍝🍰🎂🍩🍪🍫🍬🍎🍊🍋🍌🍉🍇🍓🍑🥕🌽🍺🍶☕🍵🥤",
        "乗り物" to "🚗🚕🚌🚎🚓🚑🚒🚲🛵🏍️🚃🚄🚅✈️🚀🚢⛵🏠🏢🏫🏥🏪⛩️🗼🗻",
        "記号" to "✅❌⭕❗❓⚠️🚫💡🔔📌📎✏️📝📅📞📱💻🖥️📷🔍🔒🔑📧📦💴💰⏰⌛➡️⬅️⬆️⬇️🔁",
    )
    private var emojiGrid: LinearLayout? = null

    private fun recentEmoji(): String =
        getSharedPreferences("recent", MODE_PRIVATE).getString("emoji", "") ?: ""

    private fun addRecentEmoji(e: String) {
        val list = ArrayList<String>()
        list.add(e)
        for (x in codePoints(recentEmoji())) if (x != e) list.add(x)
        val joined = list.take(32).joinToString("")
        getSharedPreferences("recent", MODE_PRIVATE).edit().putString("emoji", joined).apply()
    }

    private fun codePoints(s: String): List<String> {
        // 絵文字は結合文字（異体字選択子など）を含むため、基底の文字ごとにまとめる
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            var j = i + Character.charCount(cp)
            while (j < s.length) {
                val n = s.codePointAt(j)
                if (n == 0xFE0F || n == 0x200D || n in 0x1F3FB..0x1F3FF) {
                    j += Character.charCount(n)
                    if (n == 0x200D && j < s.length) j += Character.charCount(s.codePointAt(j))
                } else break
            }
            out.add(s.substring(i, j))
            i = j
        }
        return out
    }

    private fun buildEmojiView(pal: Palette): LinearLayout {
        fun tab(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(pal.text)
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pal.special)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(
            tab("閉じる") { closePanel() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(4), dp(4), dp(3), dp(4)) }
        )
        tabs.addView(
            tab("削除") { onBackspace() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(3), dp(4), dp(8), dp(4)) }
        )
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catRow.addView(
            tab("最近") { showEmoji(recentEmoji(), pal) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        )
        for ((name, chars) in emojiCategories) {
            catRow.addView(
                tab(name) { showEmoji(chars, pal) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            )
        }
        tabs.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(catRow)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojiGrid = grid
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun showEmoji(chars: String, pal: Palette) {
        val grid = emojiGrid ?: return
        grid.removeAllViews()
        val columns = 8
        val items = codePoints(chars)
        var row: LinearLayout? = null
        for ((i, e) in items.withIndex()) {
            if (i % columns == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            }
            row?.addView(
                TextView(this).apply {
                    text = e
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        currentInputConnection?.commitText(e, 1)
                        addRecentEmoji(e)
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    // ---- 設定画面 ----

    private fun openSettings() {
        currentInputConnection?.let { if (japanese) commitAsIs(it) }
        val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "設定を開けませんでした", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 音声入力 ----

    private fun startVoice() {
        currentInputConnection?.let { if (japanese) commitAsIs(it) }
        val i = Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "音声入力を開始できませんでした", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPanel(emoji: Boolean) {
        if (panel.visibility == View.VISIBLE && candView.visibility != View.VISIBLE &&
            (emojiView.visibility == View.VISIBLE) == emoji
        ) {
            closePanel()
            return
        }
        currentInputConnection?.let { if (japanese) commitAsIs(it) }
        candView.visibility = View.GONE
        if (emoji) {
            editView.visibility = View.GONE
            emojiView.visibility = View.VISIBLE
            val rec = recentEmoji()
            showEmoji(if (rec.isNotEmpty()) rec else emojiCategories[0].second, Theme.of(this))
        } else {
            emojiView.visibility = View.GONE
            editView.visibility = View.VISIBLE
            recordClip() // 起動前にコピーしたものも拾う
            refreshClipList()
        }
        panel.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        kb.visibility = View.VISIBLE
    }

    private fun moveCursor(d: Int) {
        resetSuggest()
        sendDownUpKeyEvents(if (d < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun editAction(id: Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun refreshClipList() {
        val pal = Theme.of(this)
        clipList.removeAllViews()
        val pinned = clipStore.pinnedList()
        val hist = clipStore.historyList()

        // 場面: 入力先のアプリ（と画面上部の文字）に合うものを自動で選ぶ。手動で選び直すこともできる。
        val store = SceneStore.instance(this)
        val scenes = store.all()
        var active: Scene? = null
        if (scenes.isNotEmpty()) {
            val pk = inputPkg
            val titles: List<String> = if (pk != null && pk in store.watchedPackages()) ScreenTitle.read(pk) else emptyList()
            val auto = store.match(inputPkg, titles)
            active = if (sceneChoice == null) auto else scenes.firstOrNull { it.id == sceneChoice }
            clipList.addView(sceneChips(scenes, auto, pal))
            if (pk != null && pk in store.watchedPackages()) {
                clipList.addView(TextView(this).apply {
                    text = when {
                        !ScreenTitle.isRunning() -> "画面上部: 読み取れません（端末のユーザー補助で「BigKey 画面の見出し読み取り」をオンにしてください）"
                        titles.isEmpty() -> "画面上部: 文字を読み取れていません"
                        else -> "画面上部: " + titles.joinToString(" / ").let { if (it.length > 70) it.take(70) + "…" else it }
                    }
                    textSize = 11f
                    setTextColor(pal.hint)
                    setPadding(dp(14), dp(2), dp(14), dp(2))
                })
            }
        }
        val sceneSnips = active?.snippets ?: emptyList()

        if (pinned.isEmpty() && hist.isEmpty() && sceneSnips.isEmpty()) {
            clipList.addView(TextView(this).apply {
                text = if (Prefs.clipRecord(this@BigKeyService)) "コピーした文がここに並びます。定型文は設定画面からも登録できます" else "履歴の記録は設定でオフになっています"
                setTextColor(pal.hint)
                textSize = 14f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        if (active != null && sceneSnips.isNotEmpty()) {
            clipList.addView(sectionLabel("場面: ${active.name}", pal))
            for (t in sceneSnips) addClipRow(t, true, pal, scene = true)
        }
        if (pinned.isNotEmpty()) {
            clipList.addView(sectionLabel(if (active != null) "共通の定型文" else "定型文", pal))
            for (t in pinned) addClipRow(t, true, pal)
        }
        if (hist.isNotEmpty()) {
            clipList.addView(sectionLabel("履歴", pal))
            for (t in hist) addClipRow(t, false, pal)
        }
    }

    private fun sceneChips(scenes: List<Scene>, auto: Scene?, pal: Palette): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        fun chip(label: String, selected: Boolean, onTap: () -> Unit) {
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) pal.accentText else pal.text)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (selected) pal.accent else pal.key)
                        cornerRadius = dp(16).toFloat()
                    }
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { onTap() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(6), 0)
                }
            )
        }
        chip("自動" + (auto?.let { "（${it.name}）" } ?: ""), sceneChoice == null) {
            sceneChoice = null
            refreshClipList()
        }
        for (sc in scenes) {
            chip(sc.name, sceneChoice == sc.id) {
                sceneChoice = sc.id
                refreshClipList()
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun sectionLabel(label: String, pal: Palette) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(pal.hint)
        setPadding(dp(14), dp(8), dp(14), dp(2))
    }

    private fun addClipRow(text: String, pinned: Boolean, pal: Palette, scene: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pal.key)
                cornerRadius = dp(12).toFloat()
            }
        }
        row.addView(
            TextView(this).apply {
                this.text = text
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(pal.text)
                setPadding(dp(14), dp(10), dp(8), dp(10))
                setOnClickListener {
                    currentInputConnection?.commitText(text, 1)
                    closePanel()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (!scene) row.addView(
            TextView(this).apply {
                this.text = if (pinned) "登録済み" else "定型文に登録"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (pinned) pal.accentText else pal.hint)
                if (pinned) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(pal.accent)
                        cornerRadius = dp(14).toFloat()
                    }
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    clipStore.togglePin(text)
                    refreshClipList()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }
        )
        clipList.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(3), dp(8), dp(3))
            }
        )
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
            val spec = InlinePresentationSpec.Builder(Size(dp(100), dp(40)), Size(dp(400), dp(48)))
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
