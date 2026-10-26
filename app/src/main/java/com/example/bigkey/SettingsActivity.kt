package com.example.bigkey

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : ComponentActivity() {

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private var refreshUserDict: (() -> Unit)? = null

    private val dictImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("ファイルを開けません")
                    val pairs = DictFile.parse(DictFile.decode(bytes))
                    val n = UserDict.instance(this).addAll(pairs)
                    toast("${n}件を追加しました（読み込んだ行: ${pairs.size}）")
                    refreshUserDict?.invoke()
                } catch (e: Exception) {
                    toast("読み込みに失敗しました: ${e.message}")
                }
            }
        }

    private val dictExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                try {
                    val text = DictFile.format(UserDict.instance(this).all())
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("書き込み先を開けません")
                    toast("書き出しました")
                } catch (e: Exception) {
                    toast("書き出しに失敗しました: ${e.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = Prefs.get(this)
        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun heading(t: String) = TextView(this).apply {
            text = t
            textSize = 16f
            setPadding(0, dp(20), 0, dp(6))
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        fun switchRow(label: String, key: String, default: Boolean) = Switch(this).apply {
            text = label
            isChecked = sp.getBoolean(key, default)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> sp.edit().putBoolean(key, checked).apply() }
        }

        root.addView(heading("1. キーボードを有効にする"))
        root.addView(button("入力方法の設定を開く") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        root.addView(heading("2. このキーボードに切り替える"))
        root.addView(button("キーボードの切り替えを開く") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        root.addView(heading("入力テスト"))
        root.addView(
            EditText(this).apply {
                hint = "ここに入力して試す"
                minLines = 3
                gravity = android.view.Gravity.TOP
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val heightLabel = TextView(this).apply {
            text = "キーの高さ: ${Prefs.keyHeightDp(this@SettingsActivity)}dp"
            textSize = 16f
            setPadding(0, dp(20), 0, dp(6))
        }
        root.addView(heightLabel)
        root.addView(SeekBar(this).apply {
            max = Prefs.MAX_HEIGHT - Prefs.MIN_HEIGHT
            progress = Prefs.keyHeightDp(this@SettingsActivity) - Prefs.MIN_HEIGHT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + Prefs.MIN_HEIGHT
                    sp.edit().putInt(Prefs.KEY_HEIGHT_DP, v).apply()
                    heightLabel.text = "キーの高さ: ${v}dp"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        root.addView(switchRow("数字の段を常に表示する", Prefs.SHOW_NUMBER_ROW, false))
        root.addView(switchRow("誤タップ補正（ローマ字として続かないキーを選びにくくする）", Prefs.HIT_CORRECTION, true))
        root.addView(switchRow("キーを押したときに振動する", Prefs.HAPTIC, true))
        root.addView(switchRow("自動変換（打ちながら漢字に変換して表示する）", Prefs.AUTO_CONVERT, true))
        root.addView(switchRow("クリップボードの履歴を記録する（履歴は終了すると消える。固定した文だけ保存）", Prefs.CLIP_RECORD, true))
        root.addView(switchRow("Bitwarden のボタンと自動入力の候補を表示する", Prefs.SHOW_BITWARDEN, true))
        root.addView(TextView(this).apply {
            text = "自動入力の候補を出すには、端末の設定で Bitwarden を自動入力サービスに選び、「インライン候補」を有効にしてください。"
            textSize = 13f
        })

        root.addView(heading("バックアップ（設定と学習履歴）"))
        root.addView(button("ファイルに書き出す") { exportLauncher.launch("bigkey-backup.json") })
        root.addView(button("ファイルから復元する") { importLauncher.launch(arrayOf("*/*")) })

        root.addView(heading("定型文"))
        val snippetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(snippetBox)
        fun refreshSnippets() {
            snippetBox.removeAllViews()
            val store = ClipStore.instance(this)
            for (text in store.pinnedList()) {
                snippetBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@SettingsActivity).apply {
                            this.text = text
                            textSize = 15f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(Button(this@SettingsActivity).apply {
                        this.text = "削除"
                        setOnClickListener {
                            store.removePinned(text)
                            refreshSnippets()
                        }
                    })
                })
            }
            if (store.pinnedList().isEmpty()) {
                snippetBox.addView(TextView(this).apply {
                    text = "まだ登録がありません"
                    textSize = 13f
                })
            }
        }
        refreshSnippets()
        root.addView(button("定型文を追加する") {
            val input = EditText(this).apply {
                hint = "例: お世話になっております。"
                minLines = 2
                gravity = android.view.Gravity.TOP
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("定型文を追加")
                .setView(input)
                .setPositiveButton("追加") { _, _ ->
                    ClipStore.instance(this).addPinned(input.text.toString())
                    refreshSnippets()
                }
                .setNegativeButton("やめる", null)
                .show()
        })
        root.addView(TextView(this).apply {
            text = "キーボード上の段のクリップボードのアイコンを押すと、定型文が一番上に並びます。タップするとその位置に入力されます。"
            textSize = 13f
        })

        root.addView(heading("場面ごとの定型文"))
        val sceneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sceneBox)
        lateinit var refreshScenes: () -> Unit

        fun appLabel(pkg: String): String {
            if (pkg.isEmpty()) return "すべてのアプリ"
            return try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }

        fun editScene(id: String?) {
            val store = SceneStore.instance(this)
            val cur = id?.let { store.get(it) }
            var pkg = cur?.pkg ?: ""
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
            }
            val nameIn = EditText(this).apply {
                hint = "場面の名前（例: 田中さんとのLINE）"
                setText(cur?.name ?: "")
                setSingleLine()
            }
            val kwIn = EditText(this).apply {
                hint = "画面上部の文字（例: 田中）。空なら、アプリだけで判定"
                setText(cur?.keyword ?: "")
                setSingleLine()
            }
            val appBtn = Button(this)
            appBtn.text = "対象のアプリ: " + appLabel(pkg)
            appBtn.setOnClickListener {
                val pm = packageManager
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second }
                val labels = (listOf("すべてのアプリ") + apps.map { it.second }).toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("対象のアプリ")
                    .setItems(labels) { _, which ->
                        pkg = if (which == 0) "" else apps[which - 1].first
                        appBtn.text = "対象のアプリ: " + appLabel(pkg)
                    }
                    .show()
            }
            box.addView(nameIn)
            box.addView(appBtn)
            box.addView(kwIn)
            android.app.AlertDialog.Builder(this)
                .setTitle(if (cur == null) "場面を追加" else "場面を編集")
                .setView(box)
                .setPositiveButton("保存") { _, _ ->
                    val n = nameIn.text.toString().trim()
                    val kw = kwIn.text.toString().trim()
                    if (n.isEmpty()) {
                        toast("名前を入れてください")
                    } else if (kw.isNotEmpty() && pkg.isEmpty()) {
                        toast("画面上部の文字を使うときは、対象のアプリも選んでください")
                    } else {
                        if (cur == null) store.add(n, pkg, kw) else store.update(cur.id, n, pkg, kw)
                        refreshScenes()
                    }
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        fun addSceneSnippet(id: String) {
            val input = EditText(this).apply {
                hint = "例: 了解です。あとで連絡します"
                minLines = 2
                gravity = android.view.Gravity.TOP
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("この場面の定型文を追加")
                .setView(input)
                .setPositiveButton("追加") { _, _ ->
                    SceneStore.instance(this).addSnippet(id, input.text.toString())
                    refreshScenes()
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        refreshScenes = {
            sceneBox.removeAllViews()
            val store = SceneStore.instance(this)
            val list = store.all()
            if (list.isEmpty()) {
                sceneBox.addView(TextView(this).apply {
                    text = "まだ場面がありません"
                    textSize = 13f
                })
            }
            for (sc in list) {
                sceneBox.addView(TextView(this).apply {
                    text = sc.name
                    textSize = 16f
                    setPadding(0, 24, 0, 0)
                })
                sceneBox.addView(TextView(this).apply {
                    text = "アプリ: " + appLabel(sc.pkg) + (if (sc.keyword.isNotEmpty()) " / 画面上部に「${sc.keyword}」" else "")
                    textSize = 12f
                })
                for (t in sc.snippets) {
                    sceneBox.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(
                            TextView(this@SettingsActivity).apply {
                                text = t
                                textSize = 15f
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        addView(Button(this@SettingsActivity).apply {
                            text = "削除"
                            setOnClickListener {
                                store.removeSnippet(sc.id, t)
                                refreshScenes()
                            }
                        })
                    })
                }
                sceneBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@SettingsActivity).apply {
                        text = "定型文を追加"
                        setOnClickListener { addSceneSnippet(sc.id) }
                    })
                    addView(Button(this@SettingsActivity).apply {
                        text = "場面を編集"
                        setOnClickListener { editScene(sc.id) }
                    })
                    addView(Button(this@SettingsActivity).apply {
                        text = "場面を削除"
                        setOnClickListener {
                            android.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("「${sc.name}」を削除")
                                .setMessage("この場面の定型文もすべて消えます。")
                                .setPositiveButton("削除") { _, _ ->
                                    store.remove(sc.id)
                                    refreshScenes()
                                }
                                .setNegativeButton("やめる", null)
                                .show()
                        }
                    })
                })
            }
        }
        refreshScenes()
        root.addView(button("場面を追加する") { editScene(null) })
        root.addView(TextView(this).apply {
            text = "場面に合うと、クリップボードのアイコンを開いたとき、その場面の定型文が一番上に並びます。上の「自動」「場面の名前」のボタンで手動でも切り替えられます。\n" +
                "アプリだけを指定した場面は、キーボードだけで判定できます。LINEのトーク相手など、画面上部の文字で絞るには、端末の「ユーザー補助」で「BigKey 画面の見出し読み取り」をオンにしてください。読み取るのは、場面に登録したアプリの画面の上の部分だけで、保存も送信もしません。"
            textSize = 13f
        })
        root.addView(button("ユーザー補助の設定を開く") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(heading("ユーザー辞書"))
        val dictBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dictBox)
        fun refreshDict() {
            dictBox.removeAllViews()
            val ud = UserDict.instance(this)
            val all = ud.all()
            dictBox.addView(TextView(this).apply {
                text = if (all.isEmpty()) "まだ登録がありません" else "登録数: ${all.size}件（先頭100件を表示）"
                textSize = 13f
            })
            for ((reading, surface) in all.take(100)) {
                dictBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@SettingsActivity).apply {
                            text = "$reading → $surface"
                            textSize = 15f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(Button(this@SettingsActivity).apply {
                        text = "削除"
                        setOnClickListener {
                            ud.remove(reading, surface)
                            refreshDict()
                        }
                    })
                })
            }
        }
        refreshUserDict = ::refreshDict
        refreshDict()
        root.addView(button("単語を追加する") {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val r = EditText(this).apply { hint = "読み（ひらがな）例: やまだ" }
            val w = EditText(this).apply { hint = "表記 例: 山田" }
            box.addView(r)
            box.addView(w)
            android.app.AlertDialog.Builder(this)
                .setTitle("単語を追加")
                .setView(box)
                .setPositiveButton("追加") { _, _ ->
                    if (UserDict.instance(this).add(r.text.toString(), w.text.toString())) refreshDict()
                    else toast("追加できませんでした。読みはひらがなで入力してください")
                }
                .setNegativeButton("やめる", null)
                .show()
        })
        root.addView(button("ファイルから読み込む") { dictImportLauncher.launch(arrayOf("*/*")) })
        root.addView(button("ファイルに書き出す") { dictExportLauncher.launch("bigkey-userdict.txt") })
        root.addView(TextView(this).apply {
            text = "読み込める形式: 「読み」と「表記」を、タブまたはカンマで区切った1行1語のテキスト（Google日本語入力・Mozc・MS-IMEの辞書書き出し、CSVなど）。文字コードは自動判定します。"
            textSize = 13f
        })

        root.addView(heading("タップ位置の学習"))
        root.addView(switchRow("タップのずれを学習して、押したつもりのキーに当たりやすくする", Prefs.TAP_LEARN, true))
        root.addView(button("学習したずれをリセットする") {
            sp.edit().putFloat(Prefs.TAP_DX, 0f).putFloat(Prefs.TAP_DY, 0f).apply()
            toast("リセットしました")
        })

        root.addView(heading("ツールバーのボタン"))
        val toolBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(toolBox)
        val toolNames = mapOf(
            "bw" to "Bitwarden（鍵）", "clip" to "クリップボード", "emoji" to "絵文字", "mic" to "音声入力",
            "select" to "範囲選択", "left" to "カーソル左", "right" to "カーソル右", "spacer" to "—— ここから右に寄せる ——"
        )
        fun refreshToolbar() {
            toolBox.removeAllViews()
            val order = Prefs.toolbarOrder(this).toMutableList()
            val hidden = Prefs.toolbarHidden(this).toMutableSet()
            fun save() {
                sp.edit()
                    .putString(Prefs.TOOLBAR_ORDER, order.joinToString(","))
                    .putString(Prefs.TOOLBAR_HIDDEN, hidden.joinToString(","))
                    .apply()
                refreshToolbar()
            }
            for ((i, id) in order.withIndex()) {
                toolBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (id == "spacer") {
                        addView(
                            TextView(this@SettingsActivity).apply { text = toolNames[id]; textSize = 13f },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                    } else {
                        addView(
                            Switch(this@SettingsActivity).apply {
                                text = toolNames[id]
                                isChecked = id !in hidden
                                setOnCheckedChangeListener { _, on ->
                                    if (on) hidden.remove(id) else hidden.add(id)
                                    sp.edit().putString(Prefs.TOOLBAR_HIDDEN, hidden.joinToString(",")).apply()
                                }
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                    }
                    addView(Button(this@SettingsActivity).apply {
                        text = "↑"
                        isEnabled = i > 0
                        setOnClickListener { java.util.Collections.swap(order, i, i - 1); save() }
                    })
                    addView(Button(this@SettingsActivity).apply {
                        text = "↓"
                        isEnabled = i < order.size - 1
                        setOnClickListener { java.util.Collections.swap(order, i, i + 1); save() }
                    })
                })
            }
        }
        refreshToolbar()
        root.addView(button("ボタンの並びを標準に戻す") {
            sp.edit().remove(Prefs.TOOLBAR_ORDER).remove(Prefs.TOOLBAR_HIDDEN).apply()
            refreshToolbar()
        })
        root.addView(TextView(this).apply {
            text = "左から順に並びます。「ここから右に寄せる」より後ろのボタンは、右端に寄ります。変更は次に入力欄を開いたときに反映されます。"
            textSize = 13f
        })

        root.addView(heading("候補の整理"))
        root.addView(TextView(this).apply {
            text = "候補バーの候補を長押しすると、その候補を今後出さないようにできます。"
            textSize = 13f
        })
        root.addView(button("外した候補をすべて元に戻す") {
            HiddenStore.instance(this).clear()
            toast("元に戻しました")
        })

        root.addView(heading("ライセンス"))
        root.addView(button("変換辞書のライセンスを見る") {
            val text = try {
                assets.open("licenses.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                "読み込めませんでした"
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("ライセンス")
                .setMessage(text)
                .setPositiveButton("閉じる", null)
                .show()
        })
    }

    private fun doExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(Backup.export(this).toByteArray(Charsets.UTF_8))
            } ?: error("書き込み先を開けません")
            toast("書き出しました")
        } catch (e: Exception) {
            toast("書き出しに失敗しました: ${e.message}")
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("ファイルを開けません")
            Backup.restore(this, text)
                .onSuccess {
                    toast("復元しました")
                    recreate()
                }
                .onFailure { toast("復元に失敗しました: ${it.message}") }
        } catch (e: Exception) {
            toast("復元に失敗しました: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
