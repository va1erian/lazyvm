package lang;

public class StringBasics {
    static void p(String label, Object v) { System.out.println(label + " = " + v); }

    static String codes(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) sb.append((int) s.charAt(i)).append(' ');
        return sb.toString().trim();
    }

    static String bytes(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) sb.append(b[i] & 0xFF).append(' ');
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        String s = "Hello, World";
        p("length", s.length());
        p("charAt(0)", s.charAt(0));
        p("charAt(11)", s.charAt(11));
        p("indexOf('o')", s.indexOf('o'));
        p("indexOf('o',5)", s.indexOf('o', 5));
        p("indexOf('o',9)", s.indexOf('o', 9));
        p("indexOf('z')", s.indexOf('z'));
        p("indexOf(\"World\")", s.indexOf("World"));
        p("indexOf(\"o\",5)", s.indexOf("o", 5));
        p("indexOf(\"\")", s.indexOf(""));
        p("indexOf(\"\",50)", s.indexOf("", 50));
        p("indexOf('H',-5)", s.indexOf('H', -5));
        p("lastIndexOf('o')", s.lastIndexOf('o'));
        p("lastIndexOf('o',7)", s.lastIndexOf('o', 7));
        p("lastIndexOf('o',3)", s.lastIndexOf('o', 3));
        p("lastIndexOf(\"l\")", s.lastIndexOf("l"));
        p("lastIndexOf(\"l\",9)", s.lastIndexOf("l", 9));
        p("lastIndexOf(\"xyz\")", s.lastIndexOf("xyz"));
        p("substring(7)", s.substring(7));
        p("substring(0,5)", s.substring(0, 5));
        p("substring(5,5)", "[" + s.substring(5, 5) + "]");
        p("substring(12)", "[" + s.substring(12) + "]");
        try {
            s.substring(13);
        } catch (StringIndexOutOfBoundsException e) {
            System.out.println("substring(13) throws " + e.getClass().getName());
        }
        try {
            s.substring(5, 2);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("substring(5,2) throws " + e.getClass().getName());
        }
        try {
            s.charAt(12);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("charAt(12) throws " + e.getClass().getName());
        }
        p("startsWith(\"Hell\")", s.startsWith("Hell"));
        p("startsWith(\"World\",7)", s.startsWith("World", 7));
        p("startsWith(\"\")", s.startsWith(""));
        p("endsWith(\"World\")", s.endsWith("World"));
        p("endsWith(\"\")", s.endsWith(""));
        p("endsWith(\"world\")", s.endsWith("world"));
        p("equals", s.equals("Hello, World") + " " + s.equals("hello, world") + " " + s.equals(null) + " " + s.equals(5));
        p("equalsIgnoreCase", s.equalsIgnoreCase("hELLO, wORLD") + " " + s.equalsIgnoreCase("hello") + " " + s.equalsIgnoreCase(null));
        p("compareTo", "apple".compareTo("banana") + " " + "banana".compareTo("apple") + " " + "a".compareTo("a")
            + " " + "abc".compareTo("abcd") + " " + "abcd".compareTo("abc") + " " + "".compareTo("xyz")
            + " " + "Z".compareTo("a") + " " + "abd".compareTo("abc"));
        p("compareToIgnoreCase", "ABC".compareToIgnoreCase("abd") + " " + "abc".compareToIgnoreCase("ABC"));
        p("hash empty", "".hashCode());
        p("hash a", "a".hashCode());
        p("hash hello", "hello".hashCode());
        p("hash Hello, World", s.hashCode());
        p("hash long", "The quick brown fox jumps over the lazy dog".hashCode());
        p("hash unicode", "é中￿".hashCode());
        p("hash Aa/BB", "Aa".hashCode() + " " + "BB".hashCode());
        p("trim", "[" + "  \t padded \n ".trim() + "]");
        p("trim none", "[" + "x".trim() + "] [" + "   ".trim() + "]");
        p("toUpperCase", "mixed Case 123 !".toUpperCase());
        p("toLowerCase", "MIXED Case 123 !".toLowerCase());
        p("replace char", "banana".replace('a', 'o'));
        p("replace missing char", "banana".replace('z', 'o'));
        p("replace str", "banana".replace("an", "AN"));
        p("replace grow", "aaa".replace("a", "bb"));
        p("replace delete", "banana".replace("a", ""));
        p("replace empty target", "ab".replace("", "-"));
        p("contains", s.contains("lo, W") + " " + s.contains("xyz") + " " + s.contains(""));
        p("concat", "foo".concat("bar") + " " + "foo".concat("").equals("foo"));
        p("join", String.join(", ", "a", "b", "c") + " | " + String.join("-", "solo") + " | " + String.join("/", new String[0]));
        p("isEmpty", "".isEmpty() + " " + " ".isEmpty());
        p("valueOf int", String.valueOf(-17));
        p("valueOf long", String.valueOf(1L << 40));
        p("valueOf char", String.valueOf('c'));
        p("valueOf boolean", String.valueOf(true));
        p("valueOf float", String.valueOf(1.5f));
        p("valueOf double", String.valueOf(2.25));
        p("valueOf null object", String.valueOf((Object) null));
        p("valueOf char[]", String.valueOf(new char[]{'o', 'k'}));
        char[] arr = s.toCharArray();
        p("toCharArray", arr.length + " " + arr[0] + arr[7]);
        arr[0] = 'J';
        p("toCharArray copy", s.charAt(0) + " " + new String(arr));
        p("new String(char[],1,3)", new String(arr, 1, 3));

        String ab = "ab";
        String a = "a";
        String dyn = a + "b";
        p("literal == literal", "ab" == ab);
        p("constant fold ==", ("a" + "b") == ab);
        p("dynamic == literal", dyn == ab);
        p("dynamic equals literal", dyn.equals(ab));
        p("intern ==", dyn.intern() == ab);
        p("new String(\"x\") == \"x\"", new String("x") == "x");
        p("new String(\"x\").intern() == \"x\"", new String("x").intern() == "x");
        p("intern idempotent", dyn.intern() == dyn.intern());

        byte[] utf = "Aé中".getBytes();
        p("utf8 bytes", bytes(utf));
        p("utf8 round trip", codes(new String(utf)));
        byte[] raw = {(byte) 0xE2, (byte) 0x82, (byte) 0xAC, 0x41};
        p("decode euro", codes(new String(raw)));
        p("decode slice", codes(new String(raw, 3, 1)));
        p("codePointAt", "x😀".codePointAt(1) + " " + "x😀".length());
        p("supplementary utf8", bytes("😀".getBytes()));
        p("empty string bytes", "".getBytes().length);
        p("regionMatches", "Hello".regionMatches(true, 1, "ELL", 0, 3) + " " + "Hello".regionMatches(1, "ELL", 0, 3));
        p("CharSequence", ((CharSequence) s).length() + " " + s.subSequence(0, 2));
        System.out.println("done");
    }
}
