package com.example.bigkey;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        /** 利用者が過去に「前の語 → この語」の並びで選んだ回数。 */
        default int pair(String prevSurface, String surface) {
            return 0;
        }
    }

    /** 変換候補。単語を差し替えた候補では、差し替えた範囲と表記を持つ（学習に使う）。 */
    public static final class Candidate {
        public final String text;
        public final int segStart; // 差し替えた単語の、読みの中での開始位置。最良の変換結果では -1
        public final int segEnd;
        public final String segSurface;
        public String predictReading; // 予測変換の候補では、その候補の読み全体。それ以外は null
        public String[] surfaces; // 変換結果を単語に区切った表記（学習用）。予測変換では null
        public String[] readings; // 上の各単語の読み

        Candidate(String text, int segStart, int segEnd, String segSurface) {
            this.text = text;
            this.segStart = segStart;
            this.segEnd = segEnd;
            this.segSurface = segSurface;
        }
    }

    private static final int ALT_MAX_DELTA = 3500;
    private static final int LIVE_CONFIDENT_DELTA = 1000;
    private static final int LIVE_HOLD = 2;
    private static final int PAIR_BONUS = 1500;
    private static final int USER_WORD_COST = 1500;
    private static final int PREDICT_LEN_PENALTY = 350;
    private static final int PREDICT_SCAN_MAX = 60000;

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

    private volatile Map<String, List<String>> userWords = new HashMap<>();
    private volatile int userMaxLen = 0;

    /** 利用者が登録した単語（読み → 表記の一覧）を、変換に加える。 */
    public void setUserWords(Map<String, List<String>> words) {
        int max = 0;
        for (String k : words.keySet()) {
            max = Math.max(max, k.length());
        }
        this.userWords = words;
        this.userMaxLen = max;
    }

    private byte[] predictable; // 品詞idごとに、予測変換に使ってよいなら1

    public void setPredictable(byte[] flags) {
        this.predictable = flags;
    }

    private boolean canPredict(int lid) {
        return predictable == null || (lid >= 0 && lid < predictable.length && predictable[lid] != 0);
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

    private void addUserNodes(String s, int i, List<Node> out) {
        Map<String, List<String>> words = userWords;
        int maxLen = Math.min(userMaxLen, s.length() - i);
        for (int len = 1; len <= maxLen; len++) {
            List<String> list = words.get(s.substring(i, i + len));
            if (list == null) {
                continue;
            }
            for (String surface : list) {
                out.add(new Node(i, i + len, ID_NOUN, ID_NOUN, USER_WORD_COST, surface));
            }
        }
    }

    // ---- 辞書にない文字 ----

    private static boolean isKana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || c == 'ー';
    }

    private static boolean isAllKana(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isKana(s.charAt(i))) {
                return false;
            }
        }
        return true;
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
        return run(reading, limit, bias, false);
    }

    /**
     * 自動変換（打ちながら表示する）用の文字列。確信のある単語だけ変換し、
     * 他の候補と僅差の単語（かんじ→感じ/漢字 など）と、末尾の打ちかけの2文字は、ひらがなのまま残す。
     */
    public String liveText(String reading, Bias bias) {
        List<Candidate> r = run(reading, 1, bias, true);
        return r.isEmpty() ? reading : r.get(0).text;
    }

    private List<Candidate> run(String reading, int limit, Bias bias, boolean live) {
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
            addUserNodes(reading, i, list);
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
                    if (bias != null && prev != bos && !prev.surface.isEmpty()) {
                        c -= Math.min(bias.pair(prev.surface, node.surface), BIAS_MAX_COUNT) * PAIR_BONUS;
                    }
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
        Candidate bestCand = new Candidate(bestText, -1, -1, null);
        bestCand.surfaces = new String[path.size()];
        bestCand.readings = new String[path.size()];
        for (int k = 0; k < path.size(); k++) {
            bestCand.surfaces[k] = path.get(k).surface;
            bestCand.readings[k] = reading.substring(path.get(k).start, path.get(k).end);
        }
        out.put(bestText, bestCand);

        // 1か所だけ別の単語に差し替えた候補
        List<Alt> alts = new ArrayList<>();
        int[] minDelta = new int[path.size()];
        Arrays.fill(minDelta, Integer.MAX_VALUE);
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
                if (delta > ALT_MAX_DELTA || isNoiseAlt(cur, alt)) {
                    continue;
                }
                if (delta < minDelta[k] && !isAllKana(alt.surface)) {
                    minDelta[k] = delta;
                }
                String text = head + alt.surface + tail;
                Candidate altCand = new Candidate(text, cur.start, cur.end, alt.surface);
                altCand.surfaces = bestCand.surfaces.clone();
                altCand.surfaces[k] = alt.surface;
                altCand.readings = bestCand.readings;
                alts.add(new Alt(delta, altCand));
            }
        }
        if (live) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < path.size(); k++) {
                Node nd = path.get(k);
                String rd = reading.substring(nd.start, nd.end);
                boolean confident = minDelta[k] >= LIVE_CONFIDENT_DELTA;
                boolean tailHold = nd.end > n - LIVE_HOLD; // 末尾の打ちかけの部分は、まだ変換しない
                sb.append(isAllKana(nd.surface) || (confident && !tailHold) ? nd.surface : rd);
            }
            out.clear();
            out.put(sb.toString(), new Candidate(sb.toString(), -1, -1, null));
            result.addAll(out.values());
            return result;
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

    /**
     * 予測変換。prefix で始まる、より長い読みの先頭候補を、費用の小さい順（短い読みを優先）に返す。
     * 返す候補は入力中の文字列全体を置き換えるもので、predictReading にその読みを入れる。
     */
    public List<Candidate> predict(String prefix, int limit, Bias bias) {
        List<Candidate> result = new ArrayList<>();
        int plen = prefix.length();
        if (plen == 0) {
            return result;
        }
        int idx = lowerBound(prefix, 0, plen, 0);
        List<Alt> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = idx; i < readingCount && i - idx < PREDICT_SCAN_MAX && startsWith(i, prefix, 0, plen); i++) {
            int p = recordPos(i);
            int len = dict.get(p) & 0xFF;
            if (len == plen) {
                continue; // 同じ読みは通常の変換で出る
            }
            char[] rc = new char[len];
            for (int k = 0; k < len; k++) {
                rc[k] = dict.getChar(p + 1 + 2 * k);
            }
            String reading = new String(rc);
            p += 1 + 2 * len;
            int count = dict.get(p) & 0xFF;
            p += 1;
            for (int e = 0; e < count && e < 4; e++) {
                int lid = dict.getShort(p);
                int cost = dict.getShort(p + 4);
                int sl = dict.get(p + 6) & 0xFF;
                p += 7;
                char[] chars = new char[sl];
                for (int k = 0; k < sl; k++) {
                    chars[k] = dict.getChar(p + 2 * k);
                }
                p += 2 * sl;
                String surface = new String(chars);
                if (!canPredict(lid)) {
                    continue;
                }
                if (bias != null) {
                    cost -= Math.min(bias.count(reading, surface), BIAS_MAX_COUNT) * BIAS_PER_COUNT;
                }
                int score = cost + (len - plen) * PREDICT_LEN_PENALTY;
                Candidate c = new Candidate(surface, -1, -1, null);
                c.predictReading = reading;
                found.add(new Alt(score, c));
            }
        }
        Collections.sort(found);
        for (Alt a : found) {
            if (result.size() >= limit) {
                break;
            }
            if (seen.add(a.candidate.text)) {
                result.add(a.candidate);
            }
        }
        return result;
    }

    /**
     * 差し替え候補に出さない単語: ひらがな1〜2文字の語（助詞など）を、カタカナ・数字・別の漢字に替えるもの。
     * ただし送り仮名が一致するもの（動詞・形容詞などの語幹）は出す。
     */
    private static boolean isNoiseAlt(Node cur, Node alt) {
        int len = cur.end - cur.start;
        if (len > 2) {
            return false;
        }
        boolean curKana = true;
        for (int i = 0; i < cur.surface.length(); i++) {
            if (!isKana(cur.surface.charAt(i))) {
                curKana = false;
                break;
            }
        }
        if (!curKana) {
            return false;
        }
        char a = alt.surface.charAt(0);
        boolean altKana = a >= 'ぁ' && a <= 'ゖ';
        if (altKana) {
            return false;
        }
        // 送り仮名が一致する差し替え（とっ→撮っ、あれ→荒れ、すぎ→過ぎ など）は候補に出す
        char last = cur.surface.charAt(cur.surface.length() - 1);
        return alt.surface.charAt(alt.surface.length() - 1) != last;
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
