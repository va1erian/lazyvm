package java.util;

/**
 * The shortest decimal digits of a positive double, and the decimal layouts
 * (`%f`, `%e`) built from them. Rounding is half-up on those digits, which is
 * what the oracle does: `%.2f` of 1.005 is 1.01 because the digits are 1005.
 */
final class FormatDigits {
    /** The significant digits, no leading zeros; "0" for zero. */
    private final char[] digits;
    /** The value is 0.digits x 10^point. */
    private final int point;

    FormatDigits(double v) {
        String t = Double.toString(v);
        int e = t.indexOf('E');
        int exp = 0;
        if (e >= 0) {
            exp = Integer.parseInt(t.substring(e + 1));
            t = t.substring(0, e);
        }
        int dot = t.indexOf('.');
        String all = dot < 0 ? t : t.substring(0, dot) + t.substring(dot + 1);
        int p = (dot < 0 ? t.length() : dot) + exp;
        int lead = 0;
        while (lead < all.length() && all.charAt(lead) == '0') {
            lead++;
        }
        if (lead == all.length()) {
            digits = new char[] {'0'};
            point = 1;
            return;
        }
        int last = all.length();
        while (last > lead + 1 && all.charAt(last - 1) == '0') {
            last--;
        }
        digits = all.substring(lead, last).toCharArray();
        point = p - lead;
    }

    boolean isZero() {
        return digits[0] == '0';
    }

    /** The decimal exponent after rounding to `n` significant digits (changes on 9.99 to 10.0). */
    int exponentAfterRounding(int n) {
        int[] exp = new int[1];
        roundSignificant(n, exp);
        return exp[0];
    }

    /** `n` significant digits, rounded half-up; `exp[0]` receives the scientific exponent. */
    private char[] roundSignificant(int n, int[] exp) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = i < digits.length ? digits[i] : '0';
        }
        exp[0] = point - 1;
        if (n < digits.length && digits[n] >= '5' && increment(out)) {
            exp[0]++;
        }
        return out;
    }

    /** Adds one in the last place; true if the carry ran off the front (result is 1000...). */
    private static boolean increment(char[] d) {
        int i = d.length - 1;
        while (i >= 0 && d[i] == '9') {
            d[i] = '0';
            i--;
        }
        if (i >= 0) {
            d[i]++;
            return false;
        }
        d[0] = '1';
        return true;
    }

    /** The `%e` layout of the value with `prec` fraction digits, without sign. */
    String scientific(int prec, boolean alt, boolean upper) {
        int[] exp = new int[1];
        char[] d = roundSignificant(prec + 1, exp);
        StringBuilder sb = new StringBuilder();
        sb.append(d[0]);
        if (prec > 0) {
            sb.append('.').append(d, 1, prec);
        } else if (alt) {
            sb.append('.');
        }
        sb.append(upper ? 'E' : 'e').append(exp[0] < 0 ? '-' : '+');
        int mag = Math.abs(exp[0]);
        if (mag < 10) {
            sb.append('0');
        }
        return sb.append(mag).toString();
    }

    /** The `%f` layout with `prec` fraction digits, without sign. */
    String fixed(int prec, boolean group, boolean alt) {
        int intLen = Math.max(point, 1);
        int total = intLen + prec;
        char[] r = new char[total];
        for (int i = 0; i < total; i++) {
            r[i] = digitAt(i - (intLen - point));
        }
        int cut = prec + point;
        if (cut >= 0 && cut < digits.length && digits[cut] >= '5' && increment(r)) {
            char[] bigger = new char[total + 1];
            Arrays.fill(bigger, '0');
            bigger[0] = '1';
            return layout(bigger, intLen + 1, group, alt);
        }
        return layout(r, intLen, group, alt);
    }

    private char digitAt(int i) {
        return i >= 0 && i < digits.length ? digits[i] : '0';
    }

    private static String layout(char[] r, int intLen, boolean group, boolean alt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < intLen; i++) {
            if (group && i > 0 && (intLen - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(r[i]);
        }
        if (r.length > intLen) {
            sb.append('.').append(r, intLen, r.length - intLen);
        } else if (alt) {
            sb.append('.');
        }
        return sb.toString();
    }
}
