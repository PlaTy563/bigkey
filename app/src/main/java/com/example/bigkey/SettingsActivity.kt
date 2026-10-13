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
