package lang;

public class Boxing {
    static void p(String label, Object v) { System.out.println(label + " = " + v); }

    static void parse(String s, int radix) {
        try {
            System.out.println("parseInt(\"" + s + "\"," + radix + ") = " + Integer.parseInt(s, radix));
        } catch (NumberFormatException e) {
            System.out.println("parseInt(\"" + s + "\"," + radix + ") NFE" + (radix == 10 ? ": " + e.getMessage() : ""));
        }
    }

    static void parseL(String s, int radix) {
        try {
            System.out.println("parseLong(\"" + s + "\"," + radix + ") = " + Long.parseLong(s, radix));
        } catch (NumberFormatException e) {
            System.out.println("parseLong(\"" + s + "\"," + radix + ") NFE" + (radix == 10 ? ": " + e.getMessage() : ""));
        }
    }

    public static void main(String[] args) {
        Integer i127a = 127, i127b = 127, i128a = 128, i128b = 128, m128a = -128, m128b = -128, m129a = -129, m129b = -129;
        p("Integer 127", i127a == i127b);
        p("Integer 128", i128a == i128b);
        p("Integer -128", m128a == m128b);
        p("Integer -129", m129a == m129b);
        p("Integer equals 128", i128a.equals(i128b));
        p("valueOf same", Integer.valueOf(100) == Integer.valueOf(100));
        p("new Integer differs", new Integer(5) == new Integer(5));
        Long l1 = 127L, l2 = 127L, l3 = 128L, l4 = 128L;
        p("Long 127", l1 == l2);
        p("Long 128", l3 == l4);
        p("Long equals Integer", l1.equals(127) + " " + l1.equals(127L));
        Short s1 = 100, s2 = 100, s3 = 1000, s4 = 1000;
        p("Short 100", s1 == s2);
        p("Short 1000", s3 == s4);
        Byte b1 = 5, b2 = 5;
        p("Byte any", b1 == b2 && Byte.valueOf((byte) -128) == Byte.valueOf((byte) -128));
        Character c1 = 'a', c2 = 'a', c3 = 'ሴ', c4 = 'ሴ';
        p("Character a", c1 == c2);
        p("Character 0x1234", c3 == c4);
        p("Character 127/128", (Character.valueOf((char) 127) == Character.valueOf((char) 127)) + " "
            + (Character.valueOf((char) 128) == Character.valueOf((char) 128)));
        Boolean t1 = true, t2 = Boolean.valueOf("TRUE"), f1 = false;
        p("Boolean", (t1 == t2) + " " + (f1 == Boolean.FALSE) + " " + Boolean.valueOf("yes") + " " + Boolean.parseBoolean(null));
        Double d1 = 1.0, d2 = 1.0;
        p("Double identity", d1 == d2);
        p("Double equals", d1.equals(d2) + " " + Double.valueOf(Double.NaN).equals(Double.NaN)
            + " " + Double.valueOf(0.0).equals(-0.0));

        Integer nul = null;
        try {
            int x = nul;
            System.out.println(x);
        } catch (NullPointerException e) {
            System.out.println("unboxing null: NullPointerException");
        }
        Integer sum = 0;
        for (int i = 0; i < 5; i++) sum += i;
        p("boxed sum", sum);
        Object o = 42;
        p("boxed class", o.getClass().getName());
        p("char box class", ((Object) 'x').getClass().getName() + " " + ((Object) 1.5f).getClass().getName());
        long widened = i127a;
        p("unbox widen", widened);

        parse("123", 10);
        parse("-123", 10);
        parse("+7", 10);
        parse("ff", 16);
        parse("-FF", 16);
        parse("1010", 2);
        parse("zz", 36);
        parse("2147483647", 10);
        parse("2147483648", 10);
        parse("-2147483648", 10);
        parse("abc", 10);
        parse("", 10);
        parse("12 ", 10);
        parse("-", 10);
        parse("12", 2);
        parse("99", 8);
        parseL("9223372036854775807", 10);
        parseL("-9223372036854775808", 10);
        parseL("9223372036854775808", 10);
        parseL("ff", 16);
        parseL("12L", 10);
        parseL("xyz", 10);

        p("toHexString(255)", Integer.toHexString(255));
        p("toHexString(-1)", Integer.toHexString(-1));
        p("toHexString(MIN)", Integer.toHexString(Integer.MIN_VALUE));
        p("toBinaryString(5)", Integer.toBinaryString(5));
        p("toBinaryString(-8)", Integer.toBinaryString(-8));
        p("toOctalString(64)", Integer.toOctalString(64));
        p("toOctalString(-1)", Integer.toOctalString(-1));
        p("Long.toHexString(-1)", Long.toHexString(-1L));
        p("Long.toBinaryString(-2)", Long.toBinaryString(-2L));
        p("Long.toOctalString(-1)", Long.toOctalString(-1L));
        p("Integer.toString(255,16)", Integer.toString(255, 16));
        p("Integer.toString(-255,2)", Integer.toString(-255, 2));
        p("Integer.toString(35,36)", Integer.toString(35, 36));
        p("MIN toString", Integer.toString(Integer.MIN_VALUE) + " " + Long.toString(Long.MIN_VALUE));
        p("MIN toString radix", Integer.toString(Integer.MIN_VALUE, 16) + " " + Integer.toString(Integer.MIN_VALUE, 2));
        p("MAX", Integer.MAX_VALUE + " " + Long.MAX_VALUE + " " + Short.MIN_VALUE + " " + Byte.MAX_VALUE);
        p("compare", Integer.compare(3, 5) + " " + Integer.compare(5, 3) + " " + Integer.compare(4, 4)
            + " " + Long.compare(Long.MIN_VALUE, 0) + " " + Integer.valueOf(7).compareTo(9));
        p("signum", Integer.signum(-9) + " " + Integer.signum(0) + " " + Long.signum(77L));
        p("bitCount", Integer.bitCount(0) + " " + Integer.bitCount(255) + " " + Integer.bitCount(-1) + " " + Long.bitCount(-1L));
        p("reverse", Integer.toHexString(Integer.reverse(1)) + " " + Integer.toHexString(Integer.reverse(0x12345678)));
        p("reverseBytes", Integer.toHexString(Integer.reverseBytes(0x12345678)));
        p("highestOneBit", Integer.highestOneBit(100) + " " + Integer.highestOneBit(0) + " " + Integer.highestOneBit(-1));
        p("lowestOneBit", Integer.lowestOneBit(96) + " " + Integer.lowestOneBit(0));
        p("nlz", Integer.numberOfLeadingZeros(1) + " " + Integer.numberOfLeadingZeros(0) + " " + Integer.numberOfLeadingZeros(-1)
            + " " + Long.numberOfLeadingZeros(1L));
        p("ntz", Integer.numberOfTrailingZeros(8) + " " + Integer.numberOfTrailingZeros(0) + " " + Long.numberOfTrailingZeros(0L));
        p("rotate", Integer.toHexString(Integer.rotateLeft(0x80000001, 1)) + " " + Integer.toHexString(Integer.rotateRight(1, 1)));
        p("hashCodes", Integer.valueOf(-7).hashCode() + " " + Long.valueOf(1L << 32).hashCode() + " "
            + Boolean.TRUE.hashCode() + " " + Boolean.FALSE.hashCode() + " " + Character.valueOf('a').hashCode()
            + " " + Double.valueOf(1.5).hashCode() + " " + Float.valueOf(1.5f).hashCode());

        p("isDigit", Character.isDigit('7') + " " + Character.isDigit('x'));
        p("isLetter", Character.isLetter('q') + " " + Character.isLetter('Q') + " " + Character.isLetter('5'));
        p("isLetterOrDigit", Character.isLetterOrDigit('_') + " " + Character.isLetterOrDigit('9'));
        p("isUpper/isLower", Character.isUpperCase('A') + " " + Character.isUpperCase('a') + " " + Character.isLowerCase('a'));
        p("isWhitespace", Character.isWhitespace(' ') + " " + Character.isWhitespace('\t') + " " + Character.isWhitespace('x'));
        p("toUpperCase", Character.toUpperCase('a') + "" + Character.toUpperCase('Z') + Character.toUpperCase('1'));
        p("toLowerCase", Character.toLowerCase('A') + "" + Character.toLowerCase('z') + Character.toLowerCase('!'));
        p("digit", Character.digit('7', 10) + " " + Character.digit('f', 16) + " " + Character.digit('g', 16) + " " + Character.digit('z', 36));
        p("forDigit", Character.forDigit(11, 16) + " " + Character.forDigit(5, 10));
        p("getNumericValue", Character.getNumericValue('8') + " " + Character.getNumericValue('a'));

        p("Double.compare", Double.compare(0.0, -0.0) + " " + Double.compare(-0.0, 0.0) + " " + Double.compare(1.0, 2.0)
            + " " + Double.compare(Double.NaN, Double.POSITIVE_INFINITY) + " " + Double.compare(Double.NaN, Double.NaN));
        p("Double.valueOf", Double.valueOf("1.5"));
        p("Double.parseDouble", Double.parseDouble("1e3") + " " + Double.parseDouble("-2.5E-3") + " " + Double.parseDouble("  7  ")
            + " " + Double.parseDouble("NaN") + " " + Double.parseDouble("-Infinity") + " " + Double.parseDouble(".5"));
        p("Float.parseFloat", Float.parseFloat("3.25") + " " + Float.parseFloat("1e2"));
        try {
            Double.parseDouble("abc");
        } catch (NumberFormatException e) {
            System.out.println("parseDouble(abc): " + e.getMessage());
        }
        p("bits", Long.toHexString(Double.doubleToLongBits(1.0)) + " " + Integer.toHexString(Float.floatToIntBits(1.0f))
            + " " + Double.longBitsToDouble(0x4000000000000000L) + " " + Float.intBitsToFloat(0x40490fdb));
        p("MIN_VALUE etc", Double.MIN_VALUE + " " + Float.MAX_VALUE + " " + Double.isInfinite(1 / (double) args.length));
        p("intValue etc", Double.valueOf(3.99).intValue() + " " + Integer.valueOf(300).byteValue() + " "
            + Long.valueOf(5000000000L).intValue() + " " + Integer.valueOf(7).doubleValue());
        System.out.println("done");
    }
}
