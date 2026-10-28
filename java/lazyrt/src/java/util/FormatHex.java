package java.util;

/** The `%a` layout of a finite, non-negative double, as the three parts the oracle pads around. */
final class FormatHex {
    private static final long MANTISSA = 0x000fffffffffffffL;
    private static final int HEX_DIGITS = 13;

    private FormatHex() {
    }

    /**
     * Returns {head, extension, exponent}: head is "1.8", extension the extra
     * zeros a large precision asks for (added after zero padding, so they
     * can push the result past the width) and exponent "p3". `precision` is
     * -1 when unspecified, and 0 counts as 1.
     */
    static String[] parts(double v, int precision) {
        long bits = Double.doubleToRawLongBits(v);
        long m = bits & MANTISSA;
        int biased = (int) ((bits >>> 52) & 0x7ff);
        if (biased == 0 && m == 0) {
            return new String[] {"0.0", zeros(Math.max(precision, 1) - 1), "p0"};
        }
        char lead = biased == 0 ? '0' : '1';
        int exp = biased == 0 ? -1022 : biased - 1023;
        String frac = stripZeros(hex13(m));
        String ext = "";
        if (precision >= HEX_DIGITS) {
            ext = zeros(precision - frac.length());
        } else if (precision != -1) {
            long sig = biased == 0 ? m : (m | (1L << 52));
            while (sig < (1L << 52)) {
                sig <<= 1;
                exp--;
            }
            int keep = Math.max(precision, 1);
            long fraction = sig & MANTISSA;
            lead = '1';
            frac = stripZeros(hex13(fraction));
            if (keep < frac.length()) {
                long q = roundHalfEven(fraction, 52 - 4 * keep);
                if (q >>> (4 * keep) != 0) {
                    q = 0;
                    exp++;
                }
                frac = stripZeros(pad(Long.toHexString(q), keep));
            }
            ext = zeros(keep - frac.length());
        }
        return new String[] {lead + "." + frac, ext, "p" + exp};
    }

    /** The top bits of `frac` after dropping `shift` low bits, rounded to even on an exact tie. */
    private static long roundHalfEven(long frac, int shift) {
        long q = frac >>> shift;
        long rem = frac & ((1L << shift) - 1);
        long half = 1L << (shift - 1);
        if (rem > half || (rem == half && (q & 1) == 1)) {
            q++;
        }
        return q;
    }

    private static String hex13(long m) {
        return pad(Long.toHexString(m), HEX_DIGITS);
    }

    /** Left-pads digits with zeros to width; shared with UUID.toString. */
    static String pad(String hex, int width) {
        return zeros(width - hex.length()) + hex;
    }

    private static String zeros(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    private static String stripZeros(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        return s.substring(0, end);
    }
}
