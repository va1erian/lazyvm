package util;

import java.util.*;

/**
 * Scanner's numeric and boolean tokens under Locale.ROOT: the accepted syntax, radix, range
 * checks and the InputMismatchException messages. Rules SC10-SC19 of docs/design/util-text.md.
 */
public class ScannerNumbers {
    static Scanner s(String t) { return new Scanner(t).useLocale(Locale.ROOT); }

    static String show(String t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }

    /** hasNextX, then nextX, then what next() sees: shows whether a failed nextX consumed the token. */
    static String run(String t, int radix, int kind) {
        Scanner q = s(t);
        String has;
        switch (kind) {
            case 0: has = "" + q.hasNextInt(radix); break;
            case 1: has = "" + q.hasNextLong(radix); break;
            case 2: has = "" + q.hasNextByte(radix); break;
            case 3: has = "" + q.hasNextShort(radix); break;
            case 4: has = "" + q.hasNextDouble(); break;
            case 5: has = "" + q.hasNextFloat(); break;
            default: has = "" + q.hasNextBoolean(); break;
        }
        String got;
        try {
            switch (kind) {
                case 0: got = "" + q.nextInt(radix); break;
                case 1: got = "" + q.nextLong(radix); break;
                case 2: got = "" + q.nextByte(radix); break;
                case 3: got = "" + q.nextShort(radix); break;
                case 4: got = "" + q.nextDouble(); break;
                case 5: got = "" + q.nextFloat(); break;
                default: got = "" + q.nextBoolean(); break;
            }
        } catch (InputMismatchException e) {
            got = "IME(" + show(String.valueOf(e.getMessage())) + ")";
        }
        return has + " " + got + (q.hasNext() ? " left " + show(q.next()) : "");
    }

    static void table(String label, String[] tokens, int radix, int kind) {
        for (String t : tokens) System.out.println(label + " [" + show(t) + "] " + run(t, radix, kind));
    }

    static long seed = 0x5ca17e5L;

    static int rnd(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % n);
    }

    public static void main(String[] args) {
        // SC10: decimal integers, signs, leading zeros.
        table("int", new String[] {"0", "-0", "+0", "00012", "-007", "+-5", "-+1", "--5", "1-", "1+", "-", "+", "5.", "5.0", "1e3", "0x10", "1_0", "(5)"}, 10, 0);
        // SC11: group separators (',' under ROOT): first group 1-3 digits not starting with 0, then groups of 3.
        table("group", new String[] {"1,234", "1,23", "1,2345", "12,34,567", "1234,567", ",123", "123,", "1,,234", "-1,234,567", "+1,234", "0,123", "01,234", "999,999", "1,000,000", "12,345,678"}, 10, 0);
        // SC12: non-ASCII digits (Character.isDigit in the BMP) are accepted; supplementary digits match but fail to parse.
        table("digits", new String[] {"١٢٣", "١,٢٣٤", "1٢", "１２", "५", "𝟎", "-١", "٠٠١"}, 10, 0);
        // SC13: range limits and the messages.
        table("range", new String[] {"2147483647", "2147483648", "-2147483648", "-2147483649", "2,147,483,647", "2,147,483,648", "+2147483648", "99999999999999999999", "٢١٤٧٤٨٣٦٤٨", "1٢,345,678,901"}, 10, 0);
        table("long", new String[] {"9223372036854775807", "9223372036854775808", "-9,223,372,036,854,775,808", "-9223372036854775809"}, 10, 1);
        table("byte", new String[] {"127", "128", "-128", "-129", "1,000"}, 10, 2);
        table("byte16", new String[] {"7f", "ff", "-80", "-81", "FF"}, 16, 2);
        table("short", new String[] {"32767", "32768", "-32,768", "-32769"}, 10, 3);
        // SC14: radix digits, case, prefixes.
        table("hex", new String[] {"ff", "FF", "-ff", "+ff", "7fffffff", "80000000", "-80000000", "f,fff", "0xff", "Ｆ", "z", "١f"}, 16, 0);
        // SC11: in a grouped numeral the first digit is a decimal digit 1-9 (or a non-ASCII digit), never a letter.
        table("group16", new String[] {"a,bcd", "1,fff", "f,123", "9,abc", "١,abc", "1,FFF", "ab,cde", "0,fff"}, 16, 0);
        table("r36", new String[] {"z", "Z", "zz", "-zik0zj", "zik0zk", "0xff"}, 36, 0);
        table("r2", new String[] {"101", "102", "-1111111111111111111111111111111", "10000000000000000000000000000000", "1,010"}, 2, 0);
        // SC15: decimal floating point.
        table("dbl", new String[] {"1.5", ".5", "5.", "1e5", "1E+5", "1e-5", "1.e5", ".e5", "e5", "1e", "1e+", "00.5", "-0.0", "+.5", "1.2.3", "-", ".", "1,234.5", "1,23.5", "1.234,5", "1,234e5", "1e1,000", "1.5d", "1.5f", "1_000"}, 10, 4);
        table("dbl range", new String[] {"1.7976931348623157E309", "4.9e-325", "-1e400", "123456789012345678901234567890"}, 10, 4);
        table("dbl digits", new String[] {"١.٢", "١e٢", "𝟎.5", "1.𝟎", "١.𝟎"}, 10, 4);
        // SC16: NaN and Infinity spellings; the locale's own symbols never parse.
        table("nonnum", new String[] {"NaN", "nan", "-NaN", "+NaN", "Infinity", "-Infinity", "+Infinity", "infinity", "INFINITY", "∞", "-∞", "�", "Inf"}, 10, 4);
        // SC17: hexadecimal floating point needs a point and at least one digit after it.
        table("hexdbl", new String[] {"0x1p3", "0x1.8p1", "-0x1.8p1", "+0x1.8p1", "0x.8p1", "0x1.8", "0X1.8P-1", "0x.p1", "0x1.", "0x1.8P+1", "0x١.8p1", "0x1.8p١", "0x1.8p1,0"}, 10, 4);
        table("flt", new String[] {"1.5", "3.4028236e38", "1e-46", "0x1.8p1", "NaN", "-Infinity", "1,000.25"}, 10, 5);
        // SC18: booleans ignore case only.
        table("bool", new String[] {"true", "TRUE", "tRuE", "false", "FALSE", "yes", "1", "t", "true,"}, 10, 6);
        // SC19: radix arguments.
        for (int r : new int[] {1, 2, 36, 37, 0, -1}) {
            String use, has;
            try { use = "" + s("10").useRadix(r).radix(); } catch (IllegalArgumentException e) { use = "IAE(" + e.getMessage() + ")"; }
            try { has = "" + s("10").hasNextInt(r); } catch (RuntimeException e) { has = e.getClass().getName() + "(" + e.getMessage() + ")"; }
            String next;
            try { next = "" + s("10").nextInt(r); } catch (RuntimeException e) { next = e.getClass().getName() + "(" + e.getMessage() + ")"; }
            String lng;
            try { lng = "" + s("10").hasNextLong(r); } catch (RuntimeException e) { lng = e.getClass().getName() + "(" + e.getMessage() + ")"; }
            System.out.println("radix " + r + " use " + use + " hasNextInt " + has + " nextInt " + next + " hasNextLong " + lng);
        }
        // SC14: the scanner's radix is the default for nextInt(); doubles ignore it.
        Scanner q = s("ff 10 10 1a 1.5 ff");
        System.out.println("hasNextInt(16) then nextInt(): " + q.hasNextInt(16) + " " + run1(q, 10) + " " + q.next());
        System.out.println("hasNextInt(10) then nextInt(16): " + q.hasNextInt(10) + " " + run1(q, 16));
        q.useRadix(16);
        System.out.println("useRadix(16): " + q.radix() + " " + q.hasNextInt() + " " + q.nextInt() + " " + q.hasNextInt() + " " + q.nextInt() + " " + q.hasNextDouble() + " " + q.nextDouble() + " " + q.hasNextDouble() + " " + q.hasNextInt());
        // SC10: a successful hasNextX does not consume; a failed nextX does not either.
        q = s("12 abc 7");
        System.out.println("no consume: " + q.hasNextInt() + q.hasNextInt() + " " + q.nextInt() + " " + q.hasNextInt() + " " + run1(q, 10) + " " + q.next() + " " + q.nextInt());
        // SC10: hasNextInt then a delimiter change re-reads the token.
        q = s("1,2 3");
        System.out.println("delimiter change: " + q.hasNextInt() + " " + q.useDelimiter("[ ,]").nextInt() + " " + q.nextInt() + " " + q.nextInt());
        // SC12: the default locale is never used by these tests (it differs between machines); US matches ROOT.
        System.out.println("US: " + run2(new Scanner("1,234.5 1.5").useLocale(Locale.US)));
        // Fuzz (SC10-SC17): random tokens over a small alphabet, every reader at once.
        String alpha = "0123456789,.-+eE١xaF";
        int fullShown = 0;
        long hash = 0;
        int[] accepted = new int[5];
        for (int i = 0; i < 4000; i++) {
            StringBuilder b = new StringBuilder();
            int len = 1 + rnd(9);
            for (int k = 0; k < len; k++) b.append(alpha.charAt(rnd(alpha.length())));
            String t = b.toString();
            StringBuilder r = new StringBuilder();
            for (int kind = 0; kind < 5; kind++) {
                int radix = kind == 2 ? 16 : 10;
                String one = run(t, radix, kind == 2 ? 0 : kind == 3 ? 1 : kind == 4 ? 4 : kind);
                if (one.startsWith("true")) accepted[kind]++;
                r.append(one).append(" | ");
            }
            hash = hash * 31 + r.toString().hashCode();
            if (r.indexOf("true") >= 0 && fullShown < 40) {
                fullShown++;
                System.out.println("fuzz [" + show(t) + "] " + r);
            }
        }
        System.out.println("fuzz accepted " + Arrays.toString(accepted) + " hash " + hash);
        System.out.println("done");
    }

    static String run1(Scanner q, int radix) {
        try { return "" + q.nextInt(radix); } catch (InputMismatchException e) { return "IME(" + e.getMessage() + ")"; }
    }

    static String run2(Scanner q) {
        return q.hasNextDouble() + " " + q.nextDouble() + " " + q.hasNextInt() + " " + q.nextDouble();
    }
}
