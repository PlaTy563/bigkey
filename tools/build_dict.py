#!/usr/bin/env python3
"""
Mozc(OSS版)の辞書と接続表から、BigKey用の dict.bin / conn.bin を作る。

使い方:
  python3 -I tools/build_dict.py <Mozcのdictionary_ossをダウンロードしたフォルダ> <出力先> [--max-cost N]

入力ファイル(Mozcのsrc/data/dictionary_oss/):
  dictionary00.txt .. dictionary09.txt   読み<TAB>左id<TAB>右id<TAB>費用<TAB>表記
  connection_single_column.txt           1行目=N、続くN*N行=接続費用(行番号 = 前の語の右id * N + 次の語の左id)

dict.bin (すべてビッグエンディアン):
  int  magic 0x424B4431
  int  読みの数 R
  int  読みの最大文字数
  int  offsets[R]            各読みのレコードの位置(レコード領域の先頭からのバイト数)
  レコード領域: 読みの昇順に並ぶ
    byte  読みの文字数 L
    char  読み[L]            UTF-16
    byte  候補数 E
    E個:  short 左id, short 右id, short 費用, byte 表記の文字数 S, char 表記[S]

conn.bin:
  int  N
  short cost[N*N]            cost[前の語の右id * N + 次の語の左id]
"""
import argparse
import glob
import os
import struct
import sys

MAGIC = 0x424B4431


def is_hira(s):
    return all(("ぁ" <= c <= "ゖ") or c == "ー" for c in s)


def to_kata(s):
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


def is_kata_only(s):
    return all(("ァ" <= c <= "ヶ") or c == "ー" for c in s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--max-cost", type=int, default=7500, help="これより費用が大きい候補は捨てる")
    ap.add_argument("--kata-max-cost", type=int, default=6500, help="カタカナだけの表記に使う上限")
    ap.add_argument("--per-reading", type=int, default=30, help="1つの読みに残す候補の最大数")
    ap.add_argument("--max-reading-len", type=int, default=16)
    ap.add_argument("--name-max-cost", type=int, default=5000, help="人名(姓・名)に使う上限。人名は大量にあり、変換候補の邪魔になりやすい")
    ap.add_argument("--proper-max-cost", type=int, default=5800, help="地名・組織名などの固有名詞に使う上限")
    args = ap.parse_args()

    best = {}  # (読み, 表記, 左id, 右id) -> 費用の最小値
    total = 0
    for path in sorted(glob.glob(os.path.join(args.src, "dictionary*.txt"))):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                p = line.rstrip("\n").split("\t")
                if len(p) < 5:
                    continue
                total += 1
                reading, lid, rid, cost, surface = p[0], int(p[1]), int(p[2]), int(p[3]), p[4]
                if not reading or len(reading) > args.max_reading_len or len(surface) > 30:
                    continue
                if not is_hira(reading):
                    continue
                if cost > args.max_cost:
                    continue
                if is_kata_only(surface) and cost > args.kata_max_cost:
                    continue
                if 1921 <= lid <= 1923 and cost > args.name_max_cost:
                    continue
                if (lid == 1920 or 1924 <= lid <= 1929) and cost > args.proper_max_cost:
                    continue
                key = (reading, surface, lid, rid)
                if key not in best or cost < best[key]:
                    best[key] = cost

    groups = {}
    for (reading, surface, lid, rid), cost in best.items():
        groups.setdefault(reading, []).append((cost, lid, rid, surface))
    for reading in groups:
        groups[reading].sort()
        del groups[reading][args.per_reading:]

    readings = sorted(groups)
    entries = sum(len(groups[r]) for r in readings)
    print(f"入力 {total} 行 -> 読み {len(readings)} 件 / 候補 {entries} 件", file=sys.stderr)

    records = []
    for r in readings:
        b = bytearray()
        b += struct.pack(">B", len(r)) + r.encode("utf-16-be")
        b += struct.pack(">B", len(groups[r]))
        for cost, lid, rid, surface in groups[r]:
            b += struct.pack(">hhh", lid, rid, cost)
            b += struct.pack(">B", len(surface)) + surface.encode("utf-16-be")
        records.append(bytes(b))

    os.makedirs(args.out, exist_ok=True)
    max_len = max(len(r) for r in readings)
    with open(os.path.join(args.out, "dict.bin"), "wb") as f:
        f.write(struct.pack(">iii", MAGIC, len(readings), max_len))
        off = 0
        for rec in records:
            f.write(struct.pack(">i", off))
            off += len(rec)
        for rec in records:
            f.write(rec)

    # 接続表
    with open(os.path.join(args.src, "connection_single_column.txt"), encoding="utf-8") as fh:
        n = int(fh.readline())
        out = bytearray(struct.pack(">i", n))
        count = 0
        for line in fh:
            out += struct.pack(">h", int(line))
            count += 1
    assert count == n * n, (count, n * n)
    with open(os.path.join(args.out, "conn.bin"), "wb") as f:
        f.write(out)

    # 予測変換に使える品詞の表 (pos.bin): id ごとに1バイト(1=使える)
    flags = bytearray(n)
    id_def = os.path.join(args.src, "id.def")
    if os.path.exists(id_def):
        with open(id_def, encoding="utf-8") as fh:
            for line in fh:
                head, _, rest = line.rstrip("\n").partition(" ")
                f = rest.split(",")
                if not head.isdigit() or len(f) < 6:
                    continue
                i = int(head)
                pos, sub, form = f[0], f[1], f[5]
                ok = False
                if pos == "名詞":
                    ok = sub in ("一般", "サ変接続", "副詞可能", "固有名詞") and not (1921 <= i <= 1923)
                elif pos in ("動詞", "形容詞"):
                    ok = sub == "自立" and form == "基本形"
                elif pos in ("副詞", "感動詞", "接続詞", "連体詞"):
                    ok = True
                if ok and i < n:
                    flags[i] = 1
        with open(os.path.join(args.out, "pos.bin"), "wb") as f:
            f.write(bytes(flags))
        print(f"pos.bin: 予測に使える品詞 {sum(flags)} / {n}", file=sys.stderr)

    for name in ("dict.bin", "conn.bin"):
        size = os.path.getsize(os.path.join(args.out, name))
        print(f"{name}: {size / 1024 / 1024:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
