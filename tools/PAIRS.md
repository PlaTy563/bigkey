# pairs.bin の作り方

変換の文脈判断に使う「単語の並びの統計」。会話文コーパスを辞書の表記で
最長一致分割し、隣接ペア（前の単語→次の単語）と文頭ペアを数えて作る。

## コーパス（2026-11-01 構築時）
- JESC（映画・ドラマ字幕の日英対訳コーパス、CC）の日本語側 2,801,388 文
- Tatoeba 日本語文 248,947 文（重み3倍で投入）

## 手順
```
javac PairCount.java CountPairs.java BuildPairs.java
# 第1引数: dict.bin / 第3引数: 語彙出力 / 第4引数: 生ペア出力 / 以降: コーパス:重み
java PairCount app/src/main/assets/dict.bin x vocab.txt pairs_raw.bin jesc_ja.txt:1 tatoeba_ja.txt:3
java CountPairs pairs_raw.bin vocab.txt pairs_count.tsv
# 引数: K 最小件数 出力 語彙 ペア頻度。BOS（文頭）は -Dkb で別のK、全体に -Dcap で上限
java -Dcap=800 -Dkb=600 BuildPairs 150 15 pairs.bin vocab.txt pairs_count.tsv
```

## 方式
- ポイント = K × ( log((count+0.5)/(prevTotal+50)) + 9.21 )、上限800で頭打ち
- 文頭ペアは K=600（文頭頻度の差が大きいため）
- count 15 未満のペアは収録しない
- 実行時は PairStats が (prev, surface) のハッシュ表を二分探索し、
  Viterbi の遷移コストからポイントを差し引く。学習済みペア（利用者の選択履歴）
  のボーナスとは別枠で、学習側が優先される想定の弱さに調整してある。

## 効果（2026-11-01 測定）
- 評価文82文: 1位正解 62→63件、候補6件以内 72→75件（悪化なし）
- 同音語の具体例15件: 5→8件（「昨日は雨が降っていました」
  「本当のことを言ってほしかった」「雨が降るかもしれない」が正解に）
- 残課題: 「彼に言ってほしかった」は2単語の並びでは判断できず（「に→行っ」が優勢）、
  3単語統計が必要
