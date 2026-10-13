package com.example.bigkey;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * かな漢字変換エンジン。
 *
 * 読み全体を単語に区切る全パターンのうち、「単語の費用」と「前後の品詞のつながりにくさ(接続費用)」の
 * 合計が最も小さいものを、動的計画法(ビタビ法)で求める。辞書と接続表は Mozc(OSS版) のデータから
 * tools/build_dict.py で作った dict.bin / conn.bin を使う。
 *
 * Androidに依存しないため、JVM上でそのまま動作確認できる。
 */
public final class Converter {
    private static final int MAGIC = 0x424B4431;

    // 辞書にない文字に使う品詞番号（Mozcのid.defによる）
    private static final int ID_NOUN = 1851; // 名詞,一般
    private static final int ID_NUMBER = 2044; // 名詞,数,アラビア数字
    private static final int ID_ALPHA = 2643; // 記号,アルファベット
    private static final int ID_SYMBOL = 2644; // 記号,一般
    private static final int ID_PERIOD = 2652; // 記号,句点
    private static final int ID_CLOSE = 2655; // 記号,括弧閉
    private static final int ID_OPEN = 2656; // 記号,括弧開
    private static final int ID_COMMA = 2658; // 記号,読点

    private static final int UNKNOWN_KANA_COST = 10000;

    /** 選んだ履歴1回あたりに下げる費用と、数える回数の上限。 */
    private static final int BIAS_PER_COUNT = 2000;
    private static final int BIAS_MAX_COUNT = 3;

    /** 利用者が過去に選んだ回数。(読み, 表記) の組ごとに返す。 */
    public interface Bias {
        int count(String reading, String surface);
    }

    /** 変換候補。単語を差し替えた候補では、差し替えた範囲と表記を持つ（学習に使う）。 */
    public static final class Candidate {
        public final String text;
        public final int segStart; // 差し替えた単語の、読みの中での開始位置。最良の変換結果では -1
        public final int segEnd;
        public final String segSurface;

        Candidate(String text, int segStart, int segEnd, String segSurface) {
            this.text = text;
            this.segStart = segStart;
            this.segEnd = segEnd;
            this.segSurface = segSurface;
        }
    }

    private final ByteBuffer dict;
    private final ByteBuffer conn;
    private final int readingCount;
    private final int maxReadingLen;
    private final int recordsPos;
    private final int connSize;

    private static final class Node {
        final int start;
        final int end;
        final int lid;
        final int rid;
        final int cost;
        final String surface;
        int best = Integer.MAX_VALUE;
        Node prev;

        Node(int start, int end, int lid, int rid, int cost, String surface) {
            this.start = start;
            this.end = end;
            this.lid = lid;
            this.rid = rid;
            this.cost = cost;
            this.surface = surface;
        }
    }

    private static final class Alt implements Comparable<Alt> {
        final int delta;
        final Candidate candidate;

        Alt(int delta, Candidate candidate) {
            this.delta = delta;
            this.candidate = candidate;
        }

        @Override
        public int compareTo(Alt o) {
            return Integer.compare(delta, o.delta);
        }
    }

    public static Converter load(byte[] dictBytes, byte[] connBytes) {
        return new Converter(ByteBuffer.wrap(dictBytes), ByteBuffer.wrap(connBytes));
    }

    private Converter(ByteBuffer dict, ByteBuffer conn) {
        if (dict.getInt(0) != MAGIC) {
            throw new IllegalArgumentException("dict.bin の形式が違います");
        }
        this.dict = dict;
        this.conn = conn;
        this.readingCount = dict.getInt(4);
        this.maxReadingLen = dict.getInt(8);
        this.recordsPos = 12 + 4 * readingCount;
        this.connSize = conn.getInt(0);
    }

    private int connCost(int rid, int lid) {
        return conn.getShort(4 + 2 * (rid * connSize + lid));
    }

    // ---- 辞書の検索 ----

    private int recordPos(int idx) {
        return recordsPos + dict.getInt(12 + 4 * idx);
    }

    /** idx番目の読みと、s[qs, qs+ql) を辞書順で比べる。 */
    private int compareReading(int idx, String s, int qs, int ql) {
        int p = recordPos(idx);
        int len = dict.get(p) & 0xFF;
        p += 1;
        int m = Math.min(len, ql);
        for (int k = 0; k < m; k++) {
            char a = dict.getChar(p + 2 * k);
            char b = s.charAt(qs + k);
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return Integer.compare(len, ql);
    }

    /** idx番目の読みが s[qs, qs+ql) で始まるか。 */
    private boolean startsWith(int idx, String s, int qs, int ql) {
        int p = recordPos(idx);
        int len = dict.get(p) & 0xFF;
        if (len < ql) {
            return false;
        }
        p += 1;
        for (int k = 0; k < ql; k++) {
            if (dict.getChar(p + 2 * k) != s.charAt(qs + k)) {
                return false;
            }
        }
        return true;
    }

    private int lowerBound(String s, int qs, int ql, int lo) {
        int hi = readingCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareReading(mid, s, qs, ql) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void addEntries(int idx, int start, int end, String reading, Bias bias, List<Node> out) {
        int p = recordPos(idx);
        int len = dict.get(p) & 0xFF;
        p += 1 + 2 * len;
        int count = dict.get(p) & 0xFF;
        p += 1;
        for (int e = 0; e < count; e++) {
            int lid = dict.getShort(p);
            int rid = dict.getShort(p + 2);
            int cost = dict.getShort(p + 4);
            int sl = dict.get(p + 6) & 0xFF;
            p += 7;
            char[] chars = new char[sl];
            for (int k = 0; k < sl; k++) {
                chars[k] = dict.getChar(p + 2 * k);
            }
            p += 2 * sl;
            String surface = new String(chars);
            if (bias != null) {
                cost -= Math.min(bias.count(reading, surface), BIAS_MAX_COUNT) * BIAS_PER_COUNT;
            }
            out.add(new Node(start, end, lid, rid, cost, surface));
        }
    }

    private void addDictionaryNodes(String s, int i, Bias bias, List<Node> out) {
        int maxLen = Math.min(maxReadingLen, s.length() - i);
        int lo = 0;
        for (int len = 1; len <= maxLen; len++) {
            int idx = lowerBound(s, i, len, lo);
            if (idx >= readingCount) {
                break;
            }
            lo = idx;
            if (!startsWith(idx, s, i, len)) {
                break; // これより長い読みも、この文字列では始まらない
            }
            if (compareReading(idx, s, i, len) == 0) {
                addEntries(idx, i, i + len, s.substring(i, i + len), bias, out);
            }
        }
    }

    // ---- 辞書にない文字 ----

    private static boolean isKana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || c == 'ー';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private void addUnknownNodes(String s, int i, List<Node> out) {
        int n = s.length();
        char c = s.charAt(i);
        if (isKana(c)) {
            out.add(new Node(i, i + 1, ID_NOUN, ID_NOUN, UNKNOWN_KANA_COST, String.valueOf(c)));
        } else if (isDigit(c)) {
            int j = i;
            while (j < n && isDigit(s.charAt(j))) {
                j++;
            }
            out.add(new Node(i, j, ID_NUMBER, ID_NUMBER, 1000, s.substring(i, j)));
        } else if (isAlpha(c)) {
            int j = i;
            while (j < n && isAlpha(s.charAt(j))) {
                j++;
            }
            out.add(new Node(i, j, ID_ALPHA, ID_ALPHA, 1000, s.substring(i, j)));
        } else {
            int id;
            switch (c) {
                case '、':
                case ',':
                    id = ID_COMMA;
                    break;
                case '。':
                case '.':
                case '!':
                case '?':
                case '！':
                case '？':
                    id = ID_PERIOD;
                    break;
                case '(':
                case '（':
                case '「':
                    id = ID_OPEN;
                    break;
                case ')':
                case '）':
                case '」':
                    id = ID_CLOSE;
                    break;
                default:
                    id = ID_SYMBOL;
                    break;
            }
            out.add(new Node(i, i + 1, id, id, 500, String.valueOf(c)));
        }
    }

    // ---- 変換 ----

    public List<Candidate> convert(String reading, int limit) {
        return convert(reading, limit, null);
    }

    /**
     * 読みを変換する。先頭が最も良い変換結果で、続けて「どこか1か所の単語だけを別の候補にした」変換結果を並べる。
     *
     * @param bias 利用者が過去に選んだ回数。null なら使わない。
     */
    public List<Candidate> convert(String reading, int limit, Bias bias) {
        int n = reading.length();
        List<Candidate> result = new ArrayList<>();
        if (n == 0) {
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Node>[] startsAt = new List[n];
        for (int i = 0; i < n; i++) {
            List<Node> list = new ArrayList<>();
            addDictionaryNodes(reading, i, bias, list);
            addUnknownNodes(reading, i, list);
            startsAt[i] = list;
        }

        Node bos = new Node(0, 0, 0, 0, 0, "");
        bos.best = 0;
        @SuppressWarnings("unchecked")
        List<Node>[] endsAt = new List[n + 1];
        for (int i = 0; i <= n; i++) {
            endsAt[i] = new ArrayList<>();
        }
        endsAt[0].add(bos);

        for (int pos = 0; pos < n; pos++) {
            List<Node> prevs = endsAt[pos];
            if (prevs.isEmpty()) {
                continue;
            }
            for (Node node : startsAt[pos]) {
                int bestCost = Integer.MAX_VALUE;
                Node bestPrev = null;
                for (Node prev : prevs) {
                    int c = prev.best + connCost(prev.rid, node.lid);
                    if (c < bestCost) {
                        bestCost = c;
                        bestPrev = prev;
                    }
                }
                node.best = bestCost + node.cost;
                node.prev = bestPrev;
                endsAt[node.end].add(node);
            }
        }

        Node last = null;
        int lastCost = Integer.MAX_VALUE;
        for (Node prev : endsAt[n]) {
            int c = prev.best + connCost(prev.rid, 0);
            if (c < lastCost) {
                lastCost = c;
                last = prev;
            }
        }
        if (last == null) {
            result.add(new Candidate(reading, -1, -1, null));
            return result;
        }

        List<Node> path = new ArrayList<>();
        for (Node x = last; x != null && x != bos; x = x.prev) {
            path.add(x);
        }
        Collections.reverse(path);

        Map<String, Candidate> out = new LinkedHashMap<>();
        String bestText = join(path, 0, path.size());
        out.put(bestText, new Candidate(bestText, -1, -1, null));

        // 1か所だけ別の単語に差し替えた候補
        List<Alt> alts = new ArrayList<>();
        for (int k = 0; k < path.size(); k++) {
            Node cur = path.get(k);
            Node before = k == 0 ? bos : path.get(k - 1);
            Node after = k == path.size() - 1 ? null : path.get(k + 1);
            int curCost = segmentCost(before, cur, after);
            String head = join(path, 0, k);
            String tail = join(path, k + 1, path.size());
            for (Node alt : startsAt[cur.start]) {
                if (alt.end != cur.end || alt.surface.equals(cur.surface)) {
                    continue;
                }
                int delta = segmentCost(before, alt, after) - curCost;
                String text = head + alt.surface + tail;
                alts.add(new Alt(delta, new Candidate(text, cur.start, cur.end, alt.surface)));
            }
        }
        Collections.sort(alts);
        for (Alt a : alts) {
            if (out.size() >= limit) {
                break;
            }
            if (!out.containsKey(a.candidate.text)) {
                out.put(a.candidate.text, a.candidate);
            }
        }

        result.addAll(out.values());
        return result;
    }

    private int segmentCost(Node before, Node node, Node after) {
        int c = connCost(before.rid, node.lid) + node.cost;
        c += after == null ? connCost(node.rid, 0) : connCost(node.rid, after.lid);
        return c;
    }

    private static String join(List<Node> path, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(path.get(i).surface);
        }
        return sb.toString();
    }
}
