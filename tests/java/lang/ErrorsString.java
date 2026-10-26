package lang;

public class ErrorsString {
    interface Case { void run() throws Throwable; }

    static void t(String label, Case c) {
        try {
            c.run();
            System.out.println(label + ": ok");
        } catch (Throwable e) {
            System.out.println(label + ": " + e.getClass().getName() + " | " + e.getMessage());
        }
    }

    static String s() { return "abc"; }

    public static void main(String[] args) {
        t("charAt -1", new Case(){public void run() throws Throwable { s().charAt(-1); }});
        t("charAt len", new Case(){public void run() throws Throwable { s().charAt(3); }});
        t("codePointAt -1", new Case(){public void run() throws Throwable { s().codePointAt(-1); }});
        t("codePointAt len", new Case(){public void run() throws Throwable { s().codePointAt(3); }});
        t("codePointBefore 0", new Case(){public void run() throws Throwable { s().codePointBefore(0); }});
        t("codePointBefore 4", new Case(){public void run() throws Throwable { s().codePointBefore(4); }});
        t("codePointCount -1,1", new Case(){public void run() throws Throwable { s().codePointCount(-1, 1); }});
        t("codePointCount 0,4", new Case(){public void run() throws Throwable { s().codePointCount(0, 4); }});
        t("codePointCount 2,1", new Case(){public void run() throws Throwable { s().codePointCount(2, 1); }});
        t("substring -1", new Case(){public void run() throws Throwable { s().substring(-1); }});
        t("substring 4", new Case(){public void run() throws Throwable { s().substring(4); }});
        t("substring -1,1", new Case(){public void run() throws Throwable { s().substring(-1, 1); }});
        t("substring 0,4", new Case(){public void run() throws Throwable { s().substring(0, 4); }});
        t("substring 2,1", new Case(){public void run() throws Throwable { s().substring(2, 1); }});
        t("subSequence 0,4", new Case(){public void run() throws Throwable { s().subSequence(0, 4); }});
        t("subSequence 2,1", new Case(){public void run() throws Throwable { s().subSequence(2, 1); }});
        t("subSequence -1,1", new Case(){public void run() throws Throwable { s().subSequence(-1, 1); }});
        t("getChars begin<0", new Case(){public void run() throws Throwable { s().getChars(-1, 2, new char[5], 0); }});
        t("getChars end>len", new Case(){public void run() throws Throwable { s().getChars(0, 4, new char[5], 0); }});
        t("getChars begin>end", new Case(){public void run() throws Throwable { s().getChars(2, 1, new char[5], 0); }});
        t("getChars dst<0", new Case(){public void run() throws Throwable { s().getChars(0, 2, new char[5], -1); }});
        t("getChars dst small", new Case(){public void run() throws Throwable { s().getChars(0, 3, new char[2], 0); }});
        t("getChars dst off", new Case(){public void run() throws Throwable { s().getChars(0, 3, new char[5], 3); }});
        t("getChars null", new Case(){public void run() throws Throwable { s().getChars(0, 3, null, 0); }});
        t("getBytes bad cs", new Case(){public void run() throws Throwable { s().getBytes("nope"); }});
        t("getBytes null cs", new Case(){public void run() throws Throwable { s().getBytes((String) null); }});
        t("new String(byte[],cs) bad", new Case(){public void run() throws Throwable { new String(new byte[1], "nope"); }});
        t("new String(byte[],cs) null", new Case(){public void run() throws Throwable { new String(new byte[1], (String) null); }});
        t("new String(char[]) null", new Case(){public void run() throws Throwable { new String((char[]) null); }});
        t("new String(String) null", new Case(){public void run() throws Throwable { new String((String) null); }});
        t("new String(char[],-1,1)", new Case(){public void run() throws Throwable { new String(new char[3], -1, 1); }});
        t("new String(char[],0,-1)", new Case(){public void run() throws Throwable { new String(new char[3], 0, -1); }});
        t("new String(char[],2,2)", new Case(){public void run() throws Throwable { new String(new char[3], 2, 2); }});
        t("new String(char[],4,0)", new Case(){public void run() throws Throwable { new String(new char[3], 4, 0); }});
        t("new String(byte[],-1,1)", new Case(){public void run() throws Throwable { new String(new byte[3], -1, 1); }});
        t("new String(byte[],0,-1)", new Case(){public void run() throws Throwable { new String(new byte[3], 0, -1); }});
        t("new String(byte[],2,2)", new Case(){public void run() throws Throwable { new String(new byte[3], 2, 2); }});
        t("new String(int[],-1,1)", new Case(){public void run() throws Throwable { new String(new int[3], -1, 1); }});
        t("new String(int[],0,-1)", new Case(){public void run() throws Throwable { new String(new int[3], 0, -1); }});
        t("new String(int[],2,2)", new Case(){public void run() throws Throwable { new String(new int[3], 2, 2); }});
        t("new String(int[]) cp -1", new Case(){public void run() throws Throwable { new String(new int[]{65, -1}, 0, 2); }});
        t("new String(int[]) cp big", new Case(){public void run() throws Throwable { new String(new int[]{0x110000}, 0, 1); }});
        t("valueOf(char[],-1,1)", new Case(){public void run() throws Throwable { String.valueOf(new char[3], -1, 1); }});
        t("valueOf(char[],2,2)", new Case(){public void run() throws Throwable { String.valueOf(new char[3], 2, 2); }});
        t("valueOf(char[],0,-1)", new Case(){public void run() throws Throwable { String.valueOf(new char[3], 0, -1); }});
        t("valueOf(char[]) null", new Case(){public void run() throws Throwable { String.valueOf((char[]) null); }});
        t("copyValueOf(char[],2,2)", new Case(){public void run() throws Throwable { String.copyValueOf(new char[3], 2, 2); }});
        t("regionMatches -1", new Case(){public void run() throws Throwable { System.out.println(s().regionMatches(-1, "abc", 0, 1)); }});
        t("regionMatches big", new Case(){public void run() throws Throwable { System.out.println(s().regionMatches(2, "abc", 0, 5)); }});
        t("regionMatches other", new Case(){public void run() throws Throwable { System.out.println(s().regionMatches(0, "abc", -1, 1)); }});
        t("regionMatches ic", new Case(){public void run() throws Throwable { System.out.println(s().regionMatches(true, 0, "ABC", 0, 9)); }});
        t("regionMatches null", new Case(){public void run() throws Throwable { s().regionMatches(0, null, 0, 1); }});
        t("compareTo null", new Case(){public void run() throws Throwable { s().compareTo(null); }});
        t("compareToIgnoreCase null", new Case(){public void run() throws Throwable { s().compareToIgnoreCase(null); }});
        t("concat null", new Case(){public void run() throws Throwable { s().concat(null); }});
        t("equals null", new Case(){public void run() throws Throwable { System.out.println(s().equals(null)); }});
        t("contains null", new Case(){public void run() throws Throwable { s().contains(null); }});
        t("startsWith null", new Case(){public void run() throws Throwable { s().startsWith(null); }});
        t("endsWith null", new Case(){public void run() throws Throwable { s().endsWith(null); }});
        t("indexOf(String) null", new Case(){public void run() throws Throwable { s().indexOf((String) null); }});
        t("lastIndexOf(String) null", new Case(){public void run() throws Throwable { s().lastIndexOf((String) null); }});
        t("replace null", new Case(){public void run() throws Throwable { s().replace(null, "x"); }});
        t("indexOf from -5", new Case(){public void run() throws Throwable { System.out.println(s().indexOf("b", -5) + " " + s().indexOf('b', -5)); }});
        t("indexOf from 99", new Case(){public void run() throws Throwable { System.out.println(s().indexOf("b", 99) + " " + s().indexOf('b', 99)); }});
        t("lastIndexOf from -1", new Case(){public void run() throws Throwable { System.out.println(s().lastIndexOf("b", -1) + " " + s().lastIndexOf('b', -1)); }});
        t("offsetByCodePoints -1", new Case(){public void run() throws Throwable { s().offsetByCodePoints(-1, 1); }});
        t("offsetByCodePoints 4", new Case(){public void run() throws Throwable { s().offsetByCodePoints(4, 1); }});
        t("offsetByCodePoints +9", new Case(){public void run() throws Throwable { s().offsetByCodePoints(0, 9); }});
        t("offsetByCodePoints -9", new Case(){public void run() throws Throwable { s().offsetByCodePoints(1, -9); }});
        t("join null", new Case(){public void run() throws Throwable { String.join(",", (CharSequence[]) null); }});
        System.out.println("done");
    }
}
