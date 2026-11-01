package com.example.bigkey;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 単語の並びの統計（コーパスから数えた隣接ペアの頻度）に基づくボーナス。
 * 変換の経路コストから、前の単語に続くことが多い単語ほど差し引く。
 * 形式: int 件数, long キー列（ソート済み）, short ポイント列。
 * キーは prev + '\0' + surface の FNV-1a 64bit ハッシュ。
 */
public final class PairStats {
    private final long[] keys;
    private final short[] points;

    private PairStats(long[] keys, short[] points) {
        this.keys = keys;
        this.points = points;
    }

    public static PairStats load(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int n = bb.getInt();
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = bb.getLong();
        }
        short[] points = new short[n];
        for (int i = 0; i < n; i++) {
            points[i] = bb.getShort();
        }
        return new PairStats(keys, points);
    }

    public int size() {
        return keys.length;
    }

    /** prev の直後に surface が続くことへのボーナス（コストから差し引く量）。なければ0。 */
    public int bonus(String prev, String surface) {
        long key = hash(prev, surface);
        int lo = 0, hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = keys[mid];
            if (k < key) {
                lo = mid + 1;
            } else if (k > key) {
                hi = mid - 1;
            } else {
                return points[mid];
            }
        }
        return 0;
    }

    static long hash(String prev, String surface) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < prev.length(); i++) {
            h ^= prev.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= 0;
        h *= 0x100000001b3L;
        for (int i = 0; i < surface.length(); i++) {
            h ^= surface.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
