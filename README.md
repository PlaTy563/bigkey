# BigKey

QWERTYのローマ字入力で日本語を打てる、キーを大きくしたAndroid用キーボード。

## 使い方

1. GitHubにpushする（`main`ブランチ）と、GitHub ActionsがAPKをビルドする。
2. Actionsの実行結果の `apk` からAPKを取得してインストールする（保持期間は3日）。
3. アプリ「BigKey」を開き、「入力方法の設定」で有効化、「切り替え」で選択する。

## 署名

- デバッグ署名: `keystore/debug.keystore`（標準パスワード、非秘匿）を固定で使う。ビルドごとに署名が変わらないため、上書きインストールできる。
- リリース署名: GitHub Secretsに以下を設定すると使われる。未設定ならデバッグ署名になる。
  - `KEYSTORE_BASE64`（keystoreファイルをbase64化したもの）
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
- 本物のリリースkeystoreはコミットしない（`.gitignore`で除外済み）。

## 配布

`v*` のタグをpushすると、APKがGitHub Releaseに添付される。Releaseはartifactの保存枠を消費しない。

```
git tag v0.1.0
git push origin v0.1.0
```

## 辞書・変換

- 変換辞書は Mozc (OSS版) の辞書と接続表から作った `app/src/main/assets/dict.bin` と `conn.bin`。ライセンスは `licenses.txt`（アプリの設定画面からも見られる）。
- 作り直す手順: `python3 -I tools/build_dict.py <Mozcのdictionary_ossのフォルダ> <出力先> --max-cost 6300 --kata-max-cost 5300 --per-reading 20`
- 変換は文全体の最適な区切りを探す方式（`Converter.java`）。手元での確認は `tools/ConverterCli.java`。
- 自動変換: 打ちながら先頭候補を表示する。設定でオフにできる。
- 選んだ候補は単語ごとに学習し、バックアップに含まれる。

## Bitwarden

- 候補バーの右端に Bitwarden を開くボタンを出す。
- Android 11 以上で、Bitwarden を自動入力サービスに選び、インライン候補を有効にすると、ログイン候補がキーボード上に出る。

## バックアップ

アプリ画面から、設定と学習履歴をJSONファイルに書き出し・復元できる。

## 入力モードと記号

- 左下のキーが「日本語」（青）／「English」（橙）で現在のモードを示す。スペースキーにもモード名を出す。
- 文字キーの右上に小さく出ている数字・記号は、そのキーを長押しすると入力できる（記号の画面に切り替えなくてよい）。

## 見た目

- 端末の暗い表示／明るい表示に合わせて配色が切り替わる（`Theme.kt`）。
- キーは平らな角丸で、削除・Shift・Enter は線で描いた図形。Enter は淡い青、モード表示は青（日本語）／橙（English）。
- 候補バーは背景と同じ色で、選択中の候補だけ丸い色付きの枠で示す。
