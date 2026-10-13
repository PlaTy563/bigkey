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

## 辞書

`app/src/main/assets/dict.txt` は仮の小規模辞書。形式は `よみ 候補1,候補2`。実用にするには大きな辞書に差し替える。

## バックアップ

アプリ画面から、設定と学習履歴をJSONファイルに書き出し・復元できる。
