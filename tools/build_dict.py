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

    for name in ("dict.bin", "conn.bin"):
        size = os.path.getsize(os.path.join(args.out, name))
        print(f"{name}: {size / 1024 / 1024:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
