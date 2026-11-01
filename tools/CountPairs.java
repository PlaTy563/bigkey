import java.io.*; import java.nio.file.*; import java.util.*;
public class CountPairs {
    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(args[0]));
        int n = raw.length / 8;
        long[] pairs = new long[n];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        for (int i = 0; i < n; i++) pairs[i] = bb.getLong();
        Arrays.parallelSort(pairs);
        List<String> vocab = Files.readAllLines(Paths.get(args[1]));
        try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(args[2])))) {
            long cur = pairs[0]; int cnt = 1; long distinct = 0;
            for (int i = 1; i < n; i++) {
                if (pairs[i] == cur) { cnt++; continue; }
                distinct++;
                w.println((int)(cur >> 32) + "\t" + (int)(cur & 0xFFFFFFFFL) + "\t" + cnt);
                cur = pairs[i]; cnt = 1;
            }
            distinct++;
            w.println((int)(cur >> 32) + "\t" + (int)(cur & 0xFFFFFFFFL) + "\t" + cnt);
            System.err.println("distinct pairs: " + distinct);
        }
        // 主要ペアの確認表示
        Map<String,Integer> id = new HashMap<>();
        for (int i = 0; i < vocab.size(); i++) id.put(vocab.get(i), i);
        Map<Long,Integer> counts = new HashMap<>();
        // TSVを再読込せず、ソート済み配列から二分探索で数える
        String[][] checks = {
            {"昨日","は"},{"機能","は"},{"雨","が"},{"が","降っ"},{"が","振っ"},
            {"漢字","を"},{"を","変換"},{"を","言っ"},{"を","行っ"},{"に","言っ"},{"に","行っ"},
            {"彼","に"},{"何","も"},{"も","言っ"},{"も","行っ"},{"こと","を"},{"声","に"},{"に","出し"},
            {"今日","は"},{"天気","です"},{"写真","を"},{"を","撮っ"},{"彼","は"},{"私","は"},
        };
        for (String[] c : checks) {
            Integer a = id.get(c[0]), b = id.get(c[1]);
            if (a == null || b == null) { System.out.println(c[0]+"->"+c[1]+": vocab missing"); continue; }
            long key = ((long) a << 32) | (b & 0xFFFFFFFFL);
            int idx = Arrays.binarySearch(pairs, key);
            int cnt = 0;
            if (idx >= 0) { int i = idx; while (i >= 0 && pairs[i] == key) { cnt++; i--; } i = idx + 1; while (i < n && pairs[i] == key) { cnt++; i++; } }
            System.out.println(c[0] + " -> " + c[1] + ": " + cnt);
        }
    }
}
