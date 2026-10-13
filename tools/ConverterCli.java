import com.example.bigkey.Converter;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 変換エンジンをJVM上で試す。
 * 使い方: java ConverterCli <dict.binとconn.binのフォルダ> <読みを1行ずつ書いたUTF-8のファイル>
 */
public class ConverterCli {
    private static List<String> texts(List<Converter.Candidate> list) {
        List<String> r = new ArrayList<>();
        for (Converter.Candidate c : list) {
            r.add(c.text);
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        byte[] dict = Files.readAllBytes(Paths.get(args[0], "dict.bin"));
        byte[] conn = Files.readAllBytes(Paths.get(args[0], "conn.bin"));
        long t0 = System.nanoTime();
        Converter c = Converter.load(dict, conn);
        out.printf("読み込み %.1f ms%n", (System.nanoTime() - t0) / 1e6);

        // ウォームアップ
        for (int i = 0; i < 200; i++) {
            c.convert("きょうはいいてんきですね", 8);
        }
        for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            long s = System.nanoTime();
            List<Converter.Candidate> r = c.convert(line.trim(), 6);
            double ms = (System.nanoTime() - s) / 1e6;
            out.printf("%s -> %s  (%.2f ms)%n", line.trim(), texts(r), ms);
        }

        // 履歴の反映: 「きのう→昨日」を1回選んだ場合
        String reading = "きのうはあめがふっていました";
        Converter.Bias bias = (rd, surface) -> rd.equals("きのう") && surface.equals("昨日") ? 1 : 0;
        out.println("--- 履歴の反映 (きのう→昨日 を1回選択)");
        out.println("履歴なし: " + texts(c.convert(reading, 3)));
        List<Converter.Candidate> withBias = c.convert(reading, 3, bias);
        out.println("履歴あり: " + texts(withBias));

        // 差し替え候補が、どの単語を差し替えたかの情報を持っているか
        out.println("--- 差し替え情報");
        for (Converter.Candidate cand : c.convert(reading, 4)) {
            out.println(cand.text + "  seg=[" + cand.segStart + "," + cand.segEnd + ") surface=" + cand.segSurface);
        }
    }
}
