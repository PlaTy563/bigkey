import java.io.*;
import java.nio.file.*;
import java.util.*;

/** dict.bin の表記でコーパスを最長一致分割し、隣接ペア (prevId, curId) をバイナリで出力する。 */
public class PairCount {
    public static void main(String[] args) throws Exception {
        byte[] dict = Files.readAllBytes(Paths.get(args[0]));
        // dict.bin をパースして表記の集合を作る
        HashMap<String, Integer> ids = new HashMap<>();
        ArrayList<String> surfaces = new ArrayList<>();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(dict);
        int magic = bb.getInt(); int R = bb.getInt(); int maxlen = bb.getInt();
        int[] offs = new int[R];
        for (int i = 0; i < R; i++) offs[i] = bb.getInt();
        int recpos = bb.position();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < R; i++) {
            int p = recpos + offs[i];
            int L = dict[p++] & 0xFF;
            p += 2 * L;
            int E = dict[p++] & 0xFF;
            for (int e = 0; e < E; e++) {
                p += 6;
                int S = dict[p++] & 0xFF;
                sb.setLength(0);
                for (int k = 0; k < S; k++) sb.append((char) (((dict[p++] & 0xFF) << 8) | (dict[p++] & 0xFF)));
                String s = sb.toString();
                if (!ids.containsKey(s)) { ids.put(s, surfaces.size()); surfaces.add(s); }
            }
        }
        System.err.println("vocab: " + surfaces.size());
        Files.write(Paths.get(args[2]), surfaces, java.nio.charset.StandardCharsets.UTF_8);
        // 先頭文字 -> 表記リスト（長い順）
        HashMap<Character, ArrayList<String>> byFirst = new HashMap<>();
        int maxSurface = 1;
        for (String s : surfaces) {
            if (s.isEmpty()) continue;
            byFirst.computeIfAbsent(s.charAt(0), k -> new ArrayList<>()).add(s);
            maxSurface = Math.max(maxSurface, s.length());
        }
        for (ArrayList<String> l : byFirst.values()) l.sort((a, b) -> b.length() - a.length());

        long tokens = 0, pairs = 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[3]), 1 << 20))) {
            for (int fi = 4; fi < args.length; fi++) {
                String[] parts = args[fi].split(":");
                Path file = Paths.get(parts[0]);
                int repeat = Integer.parseInt(parts[1]);
                for (int r = 0; r < repeat; r++) {
                    for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                        int prev = -2; // -2 は文頭（BOS）
                        int i = 0, n = line.length();
                        while (i < n) {
                            char c = line.charAt(i);
                            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '…' || c == '、' || c == ' ' || c == '　' || c == '「' || c == '」' || c == '（' || c == '）' || c == '(' || c == ')' || c == '・' || c == '：' || c == ':') {
                                prev = (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') ? -2 : -1;
                                i++;
                                continue;
                            }
                            ArrayList<String> cand = byFirst.get(c);
                            String hit = null;
                            if (cand != null) {
                                int remain = n - i;
                                for (String s : cand) {
                                    if (s.length() <= remain && line.startsWith(s, i)) { hit = s; break; }
                                }
                            }
                            if (hit == null) { prev = -1; i++; continue; }
                            int id = ids.get(hit);
                            tokens++;
                            if (prev == -2) { out.writeLong(((long) 0xFFFFFFFE << 32) | (id & 0xFFFFFFFFL)); pairs++; }
                            else if (prev >= 0) { out.writeLong(((long) prev << 32) | (id & 0xFFFFFFFFL)); pairs++; }
                            prev = id;
                            i += hit.length();
                        }
                    }
                }
            }
        }
        System.err.println("tokens: " + tokens + ", pairs emitted: " + pairs);
    }
}
