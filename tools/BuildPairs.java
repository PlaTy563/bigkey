import java.io.*; import java.nio.file.*; import java.util.*;
/** pairs_count.tsv + vocab.txt から pairs.bin を作る。引数: K 最小件数 出力先 */
public class BuildPairs {
    static final int CAP = Integer.parseInt(System.getProperty("cap", "6000"));
    static final double KB = Double.parseDouble(System.getProperty("kb", "150"));
    static long hash(String prev, String surface) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < prev.length(); i++) { h ^= prev.charAt(i); h *= 0x100000001b3L; }
        h ^= 0; h *= 0x100000001b3L;
        for (int i = 0; i < surface.length(); i++) { h ^= surface.charAt(i); h *= 0x100000001b3L; }
        return h;
    }
    public static void main(String[] args) throws Exception {
        double K = Double.parseDouble(args[0]);
        int minCount = Integer.parseInt(args[1]);
        List<String> vocab = Files.readAllLines(Paths.get(args[3]));
        HashMap<Integer, Long> prevTotal = new HashMap<>();
        ArrayList<int[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[4]))) {
            String[] p = line.split("\t");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
            rows.add(new int[]{a, b, c});
            prevTotal.merge(a, (long) c, Long::sum);
        }
        TreeMap<Long, Integer> table = new TreeMap<>();
        for (int[] r : rows) {
            if (r[2] < minCount) continue;
            double score = Math.log((r[2] + 0.5) / (prevTotal.get(r[0]) + 50.0));
            double kUse = r[0] == -2 ? KB : K;
            long pts = Math.round(kUse * (score + 9.21));
            if (pts < 200) continue;
            if (pts > CAP) pts = CAP;
            String prevSurface = r[0] == -2 ? "\u0000" : vocab.get(r[0]);
            table.put(hash(prevSurface, vocab.get(r[1])), (int) pts);
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[2])))) {
            out.writeInt(table.size());
            for (long k : table.keySet()) out.writeLong(k);
            for (int v : table.values()) out.writeShort(v);
        }
        System.err.println("entries: " + table.size() + " (K=" + K + ", minCount=" + minCount + ")");
    }
}
