package java.util;

/** The numeric conversions: `%d %o %x %X` for integers and `%e %f %g %a` for doubles. */
final class FormatNumbers {
    private FormatNumbers() {
    }

    /** Sign, an optional prefix such as 0x, zero padding to the width, digits, closing paren. */
    private static String assemble(FormatSpec s, boolean neg, String prefix, String digits) {
        StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append(s.has(FormatSpec.PAREN) ? '(' : '-');
        } else if (s.has(FormatSpec.PLUS)) {
            sb.append('+');
        } else if (s.has(FormatSpec.SPACE)) {
            sb.append(' ');
        }
        sb.append(prefix);
        boolean close = neg && s.has(FormatSpec.PAREN);
        if (s.has(FormatSpec.ZERO) && s.width != -1) {
            int fill = s.width - (sb.length() + digits.length() + (close ? 1 : 0));
            for (int i = 0; i < fill; i++) {
                sb.append('0');
            }
        }
        sb.append(digits);
        if (close) {
            sb.append(')');
        }
        return s.upper ? s.justify(sb.toString().toUpperCase()) : s.justify(sb.toString());
    }

    /** Formats a Byte, Short, Integer or Long; any other type is a conversion error. */
    static String integer(FormatSpec s, Object arg) {
        long v;
        long mask;
        if (arg instanceof Byte) {
            v = ((Byte) arg).byteValue();
            mask = 0xffL;
        } else if (arg instanceof Short) {
            v = ((Short) arg).shortValue();
            mask = 0xffffL;
        } else if (arg instanceof Integer) {
            v = ((Integer) arg).intValue();
            mask = 0xffffffffL;
        } else if (arg instanceof Long) {
            v = ((Long) arg).longValue();
            mask = -1L;
        } else {
            throw new IllegalFormatConversionException(s.lower(), arg.getClass());
        }
        if (s.conv == 'd') {
            return decimal(s, v);
        }
        s.rejectFlags(FormatSpec.PAREN, FormatSpec.PLUS, FormatSpec.SPACE);
        boolean hex = s.conv != 'o';
        String digits = hex ? Long.toHexString(v & mask) : Long.toOctalString(v & mask);
        String prefix = s.has(FormatSpec.ALT) ? (hex ? "0x" : "0") : "";
        return assemble(s, false, prefix, digits);
    }

    private static String decimal(FormatSpec s, long v) {
        String digits = Long.toString(v);
        boolean neg = v < 0;
        if (neg) {
            digits = digits.substring(1);
        }
        if (s.has(FormatSpec.GROUP)) {
            digits = group(digits);
        }
        return assemble(s, neg, "", digits);
    }

    private static String group(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /** Formats a Float or Double (a float is first widened to double, digits and all). */
    static String floating(FormatSpec s, Object arg) {
        double v;
        if (arg instanceof Double) {
            v = ((Double) arg).doubleValue();
        } else if (arg instanceof Float) {
            v = ((Float) arg).floatValue();
        } else {
            throw new IllegalFormatConversionException(s.lower(), arg.getClass());
        }
        if (v != v) {
            return s.justify(s.upper ? "NAN" : "NaN");
        }
        boolean neg = Double.doubleToRawLongBits(v) < 0;
        if (Double.isInfinite(v)) {
            return s.justify(signed(s, neg, s.upper ? "INFINITY" : "Infinity"));
        }
        double mag = Math.abs(v);
        if (s.conv == 'a' || s.conv == 'A') {
            return hexFloat(s, neg, mag);
        }
        return assemble(s, neg, "", decimalBody(s, mag));
    }

    /** `%a`: zero padding ignores the sign and the extra zeros of a large precision. */
    private static String hexFloat(FormatSpec s, boolean neg, double mag) {
        String[] p = FormatHex.parts(mag, s.precision);
        StringBuilder sb = new StringBuilder(signed(s, neg, ""));
        sb.append("0x");
        if (s.has(FormatSpec.ZERO)) {
            for (int i = s.width - (2 + p[0].length() + p[2].length()); i > 0; i--) {
                sb.append('0');
            }
        }
        sb.append(p[0]).append(p[1]).append(p[2]);
        return s.justify(s.upper ? sb.toString().toUpperCase() : sb.toString());
    }

    /** Wraps text in the sign or parentheses the flags ask for (no zero padding). */
    private static String signed(FormatSpec s, boolean neg, String text) {
        if (neg) {
            return s.has(FormatSpec.PAREN) ? "(" + text + ")" : "-" + text;
        }
        if (s.has(FormatSpec.PLUS)) {
            return "+" + text;
        }
        return s.has(FormatSpec.SPACE) ? " " + text : text;
    }

    private static String decimalBody(FormatSpec s, double mag) {
        FormatDigits d = new FormatDigits(mag);
        boolean group = s.has(FormatSpec.GROUP);
        boolean alt = s.has(FormatSpec.ALT);
        int prec = s.precision == -1 ? 6 : s.precision;
        switch (s.conv) {
            case 'e': case 'E':
                return d.scientific(prec, alt, s.upper);
            case 'f':
                return d.fixed(prec, group, alt);
            default:
                break;
        }
        int sig = prec == 0 ? 1 : prec;
        if (d.isZero()) {
            return d.fixed(sig - 1, group, false);
        }
        int x = d.exponentAfterRounding(sig);
        if (x >= -4 && x < sig) {
            return d.fixed(sig - (x + 1), group, false);
        }
        return d.scientific(sig - 1, false, s.upper);
    }
}
