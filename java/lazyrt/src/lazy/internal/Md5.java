package lazy.internal;

/** The MD5 message digest of RFC 1321, for UUID.nameUUIDFromBytes (util-data.md U7). */
public final class Md5 {
    private Md5() {
    }

    /** RFC 1321 section 3.4: the per-step additive constants T[1..64]. */
    private static final int[] T = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    };

    /** The left-rotation amounts of each round's four steps (section 3.4). */
    private static final int[][] SHIFTS = {{7, 12, 17, 22}, {5, 9, 14, 20}, {4, 11, 16, 23}, {6, 10, 15, 21}};

    /** The 16-byte digest of `data`. */
    public static byte[] digest(byte[] data) {
        byte[] message = pad(data);
        int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476}; // section 3.3
        int[] x = new int[16];
        for (int block = 0; block < message.length; block += 64) {
            for (int j = 0; j < 16; j++) {
                x[j] = littleEndian(message, block + 4 * j);
            }
            compress(state, x);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (state[i / 4] >>> (8 * (i % 4)));
        }
        return out;
    }

    /** Sections 3.1 and 3.2: a 1 bit, zeros to 56 mod 64 bytes, then the bit length, low byte first. */
    private static byte[] pad(byte[] data) {
        int n = data.length;
        if (n > Integer.MAX_VALUE - 72) {
            throw new OutOfMemoryError("MD5 input too large");
        }
        int padded = (n + 8) / 64 * 64 + 64;
        byte[] m = new byte[padded];
        System.arraycopy(data, 0, m, 0, n);
        m[n] = (byte) 0x80;
        long bits = (long) n * 8;
        for (int i = 0; i < 8; i++) {
            m[padded - 8 + i] = (byte) (bits >>> (8 * i));
        }
        return m;
    }

    private static int littleEndian(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    /** Section 3.4: the four rounds of sixteen steps over one 64-byte block. */
    private static void compress(int[] state, int[] x) {
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        for (int i = 0; i < 64; i++) {
            int round = i / 16;
            int next = b + Integer.rotateLeft(a + mix(round, b, c, d) + x[word(round, i)] + T[i],
                                              SHIFTS[round][i % 4]);
            a = d;
            d = c;
            c = b;
            b = next;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }

    /** The auxiliary functions F, G, H and I of section 3.4. */
    private static int mix(int round, int b, int c, int d) {
        switch (round) {
            case 0: return (b & c) | (~b & d);
            case 1: return (b & d) | (c & ~d);
            case 2: return b ^ c ^ d;
            default: return c ^ (b | ~d);
        }
    }

    /** Which message word step i of a round reads. */
    private static int word(int round, int i) {
        switch (round) {
            case 0: return i;
            case 1: return (5 * i + 1) % 16;
            case 2: return (3 * i + 5) % 16;
            default: return (7 * i) % 16;
        }
    }
}
