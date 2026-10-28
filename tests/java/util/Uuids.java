package util;

import java.util.UUID;

/** java.util.UUID (docs/design/util-data.md, section 5). */
public class Uuids {
    static long seed = 0xC0FFEEL;

    static long nextLong() {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return seed;
    }

    static String parse(String s) {
        try {
            UUID u = UUID.fromString(s);
            return u + " " + Long.toHexString(u.getMostSignificantBits()) + "/" + Long.toHexString(u.getLeastSignificantBits());
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static String time(UUID u) {
        StringBuilder sb = new StringBuilder();
        try { sb.append("ts ").append(Long.toHexString(u.timestamp())); } catch (Exception e) { sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()); }
        try { sb.append(" cs ").append(u.clockSequence()); } catch (Exception e) { sb.append(" ").append(e.getMessage()); }
        try { sb.append(" node ").append(Long.toHexString(u.node())); } catch (Exception e) { sb.append(" ").append(e.getMessage()); }
        return sb.toString();
    }

    static byte[] bytes(int n, int mul) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * mul + 1);
        return b;
    }

    public static void main(String[] args) {
        // U1: toString is 8-4-4-4-12 lower-case hex, zero padded.
        long[][] pairs = {{0, 0}, {-1, -1}, {1, 2}, {0x123456789abcdef0L, 0x0fedcba987654321L}, {Long.MIN_VALUE, Long.MAX_VALUE},
            {0x00000000ffffffffL, 0xffffffff00000000L}, {0x0000100000001000L, 0x0001000000000001L}};
        for (long[] p : pairs) {
            UUID u = new UUID(p[0], p[1]);
            System.out.println("uuid " + u + " hash " + u.hashCode() + " version " + u.version() + " variant " + u.variant()
                + " bits " + (u.getMostSignificantBits() == p[0]) + (u.getLeastSignificantBits() == p[1]));
        }
        // U2-U4: fromString: five dash-separated hex fields, unmasked shifts, Long.decode-style errors.
        String[] in = {"1-2-3-4-5", "0-0-0-0-0", "ffffffff-ffff-ffff-ffff-ffffffffffff", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",
            "AbCdEf01-2345-6789-aBcD-ef0123456789", "123456789-1-1-1-1", "1-12345-1-1-1", "1-1-12345-1-1", "1-1-1-12345-1",
            "1-1-1-1-1234567890123", "1-1-1-1-12345678901234567", "fffffffff-fffff-fffff-fffff-fffffffffffff", "1-1-1-1", "1-1-1-1-1-1",
            "-1-1-1-1", "1--1-1-1", "", "-", "----", "-----", "g-1-1-1-1", "+1-1-1-1-1", "-1-1-1-1-1", "1-1-1-1-+1", "1-1-1-1--1",
            "1-1-1-1-+", "1-1-1-1--", "0x1-1-1-1-1", "#1-1-1-1-1", " 1-1-1-1-1", "1-1-1-1-1 ", "1-1-1-1-ffffffffffffffff",
            "1-1-1-1-10000000000000000", "1-1-1-1-8000000000000000", "1-1-1-1-7fffffffffffffff", "1-1-1-1-", "1-1-1--1-1",
            "12345678123456781234567812345678", "00000000-0000-1000-8000-000000000000", "1-1-1-1-1-", "1-1-1-1-1--",
            "1-1-1-1-1---", "0-0-0-0-0000000000000000000000001", "8000000000000000-0-0-0-0", "-0-0-0-0", "1-1-1-1-0x1",
            "1-1-1-1-#1", "1-1-1-1-01", "١-1-1-1-1", "１-1-1-1-1"};
        for (String s : in) System.out.println("parse [" + s + "] " + parse(s));
        try {
            UUID.fromString(null);
        } catch (NullPointerException e) {
            System.out.println("parse null NPE");
        }
        // U5: variant from the top bits of the low word; version from bits 12-15 of the high word.
        StringBuilder sb = new StringBuilder("variants");
        for (int top = 0; top < 16; top++) sb.append(' ').append(new UUID(0, (long) top << 60).variant());
        sb.append(" | versions");
        for (int v = 0; v < 16; v++) sb.append(' ').append(new UUID((long) v << 12 | 0xffffffffffff0fffL, 0).version());
        System.out.println(sb);
        // U6: time fields only for version 1, whatever the variant.
        UUID t = new UUID(0x0123456789ab1cdeL, 0x9234567890abcdefL);
        System.out.println("v1 " + t + " " + time(t));
        System.out.println("v1 variant 0 " + time(new UUID(0x0123456789ab1cdeL, 0x1234567890abcdefL)));
        System.out.println("v1 all ones " + time(new UUID(0xffffffffffff1fffL, -1L)));
        System.out.println("v1 zero " + time(new UUID(0x1000L, 0)));
        System.out.println("v4 " + time(new UUID(0x4000L, 0x8000000000000000L)));
        System.out.println("v0 " + time(new UUID(0, 0)));
        // U7: compareTo orders signed high words, then signed low words; equals compares both words.
        long[] ks = {Long.MIN_VALUE, -2, -1, 0, 1, Long.MAX_VALUE};
        sb = new StringBuilder("compareTo");
        for (long a : ks) for (long b : ks) sb.append(' ').append(new UUID(a, 0).compareTo(new UUID(b, 0)))
            .append(new UUID(5, a).compareTo(new UUID(5, b)));
        System.out.println(sb);
        System.out.println("compare high first " + new UUID(1, Long.MIN_VALUE).compareTo(new UUID(0, Long.MAX_VALUE))
            + " equals " + new UUID(1, 2).equals(new UUID(1, 2)) + new UUID(1, 2).equals(new UUID(2, 1)) + new UUID(1, 2).equals(null)
            + new UUID(1, 2).equals("00000000-0000-0001-0000-000000000002"));
        try {
            new UUID(1, 2).compareTo(null);
        } catch (NullPointerException e) {
            System.out.println("compareTo null NPE");
        }
        // U8: nameUUIDFromBytes is MD5 with version 3 and variant 2; lengths around the 64-byte MD5 block.
        int[] lens = {0, 1, 2, 3, 15, 16, 31, 32, 54, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129, 1000};
        for (int n : lens) {
            UUID u = UUID.nameUUIDFromBytes(bytes(n, 37));
            System.out.println("md5 " + n + " " + u + " " + u.version() + u.variant());
        }
        System.out.println("md5 abc " + UUID.nameUUIDFromBytes(new byte[] {'a', 'b', 'c'}) + " zeros64 " + UUID.nameUUIDFromBytes(new byte[64])
            + " ff " + UUID.nameUUIDFromBytes(new byte[] {(byte) 0xff}));
        try {
            UUID.nameUUIDFromBytes(null);
        } catch (NullPointerException e) {
            System.out.println("md5 null NPE");
        }
        // U9: randomUUID is version 4, variant 2, and two calls differ (values are never printed).
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        System.out.println("random " + r1.version() + " " + r1.variant() + " differ " + !r1.equals(r2) + " len " + r1.toString().length());
        // Fuzz U1-U4, U7, U8: random words; round trip through toString, checksums of text, hash and order.
        int sum = 0, round = 0, okParse = 0;
        UUID prev = new UUID(0, 0);
        for (int i = 0; i < 2000; i++) {
            long hi = nextLong(), lo = nextLong();
            if (i % 7 == 0) hi &= 0xffffL;
            if (i % 11 == 0) lo = -lo;
            UUID u = new UUID(hi, lo);
            String s = u.toString();
            if (UUID.fromString(s).equals(u) && UUID.fromString(s.toUpperCase()).equals(u)) okParse++;
            sum = sum * 31 + s.hashCode();
            sum = sum * 31 + u.hashCode();
            sum = sum * 31 + u.compareTo(prev);
            sum = sum * 31 + u.version() * 8 + u.variant();
            prev = u;
            byte[] name = new byte[i % 150];
            for (int k = 0; k < name.length; k++) name[k] = (byte) nextLong();
            sum = sum * 31 + UUID.nameUUIDFromBytes(name).hashCode();
            round++;
        }
        System.out.println("fuzz rounds " + round + " parse " + okParse + " sum " + sum);
        // U2: short fields parse as their numeric value.
        sum = 0;
        for (int i = 0; i < 500; i++) {
            StringBuilder f = new StringBuilder();
            for (int k = 0; k < 5; k++) {
                if (k > 0) f.append('-');
                String h = Long.toHexString(nextLong() >>> 1);
                f.append(h.substring(0, 1 + (int) ((nextLong() >>> 1) % Math.min(h.length(), 15))));
            }
            sum = sum * 31 + parse(f.toString()).hashCode();
        }
        System.out.println("fuzz short fields " + sum);
        System.out.println("done");
    }
}
