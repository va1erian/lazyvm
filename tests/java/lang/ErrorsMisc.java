package lang;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Objects;

public class ErrorsMisc {
    interface Case { void run() throws Throwable; }

    enum A { X, Y }
    enum B { P }

    static void t(String label, Case c) {
        try {
            c.run();
            System.out.println(label + ": ok");
        } catch (Throwable e) {
            System.out.println(label + ": " + e.getClass().getName() + " | " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int cmp(Enum a, Enum b) { return a.compareTo(b); }

    public static void main(String[] args) {
        t("requireNonNull", new Case(){public void run() throws Throwable { Objects.requireNonNull(null); }});
        t("requireNonNull msg", new Case(){public void run() throws Throwable { Objects.requireNonNull(null, "boom"); }});
        t("initCause twice", new Case(){public void run() throws Throwable { Throwable e = new RuntimeException("a"); e.initCause(new Error("b")); e.initCause(new Error("c")); }});
        t("initCause twice (cause ctor)", new Case(){public void run() throws Throwable { new RuntimeException("a", null).initCause(new Error("c")); }});
        t("initCause self", new Case(){public void run() throws Throwable { Throwable e = new RuntimeException("a"); e.initCause(e); }});
        t("addSuppressed null", new Case(){public void run() throws Throwable { new RuntimeException("a").addSuppressed(null); }});
        t("addSuppressed self", new Case(){public void run() throws Throwable { Throwable e = new RuntimeException("a"); e.addSuppressed(e); }});
        t("copyOf neg", new Case(){public void run() throws Throwable { Arrays.copyOf(new int[2], -1); }});
        t("copyOf null", new Case(){public void run() throws Throwable { Arrays.copyOf((int[]) null, 1); }});
        t("copyOfRange from>to", new Case(){public void run() throws Throwable { Arrays.copyOfRange(new int[5], 3, 1); }});
        t("copyOfRange from<0", new Case(){public void run() throws Throwable { Arrays.copyOfRange(new int[5], -1, 1); }});
        t("copyOfRange from>len", new Case(){public void run() throws Throwable { Arrays.copyOfRange(new int[5], 6, 7); }});
        t("fill from>to", new Case(){public void run() throws Throwable { Arrays.fill(new int[5], 3, 1, 0); }});
        t("fill from<0", new Case(){public void run() throws Throwable { Arrays.fill(new int[5], -1, 1, 0); }});
        t("fill to>len", new Case(){public void run() throws Throwable { Arrays.fill(new int[5], 0, 6, 0); }});
        t("fill Object from>to", new Case(){public void run() throws Throwable { Arrays.fill(new String[5], 3, 1, "x"); }});
        t("fill Object to>len", new Case(){public void run() throws Throwable { Arrays.fill(new String[5], 0, 6, "x"); }});
        t("forName no.Such", new Case(){public void run() throws Throwable { Class.forName("no.Such"); }});
        t("forName slashes", new Case(){public void run() throws Throwable { Class.forName("java/lang/String"); }});
        t("forName empty", new Case(){public void run() throws Throwable { Class.forName(""); }});
        t("forName null", new Case(){public void run() throws Throwable { Class.forName(null); }});
        t("Class.cast", new Case(){public void run() throws Throwable { try { String.class.cast(Integer.valueOf(1)); } catch (ClassCastException e) { System.out.println("CCE"); throw e; } }});
        t("Enum.compareTo", new Case(){public void run() throws Throwable { cmp(A.X, B.P); }});
        t("Thread.sleep -1", new Case(){public void run() throws Throwable { Thread.sleep(-1); }});
        t("setPriority 11", new Case(){public void run() throws Throwable { new Thread().setPriority(11); }});
        t("setPriority 0", new Case(){public void run() throws Throwable { new Thread().setPriority(0); }});
        t("Object.wait -1", new Case(){public void run() throws Throwable { new Object().wait(-1); }});
        t("getProperty null", new Case(){public void run() throws Throwable { System.getProperty(null); }});
        t("getProperty empty", new Case(){public void run() throws Throwable { System.getProperty(""); }});
        t("getProperty def null", new Case(){public void run() throws Throwable { System.getProperty(null, "d"); }});
        t("array neg", new Case(){public void run() throws Throwable { int n = -1; int[] a = new int[n]; }});
        t("array idx", new Case(){public void run() throws Throwable { int[] a = new int[2]; a[2] = 1; }});
        t("array idx -1", new Case(){public void run() throws Throwable { int[] a = new int[2]; System.out.println(a[-1]); }});
        t("array store", new Case(){public void run() throws Throwable { Object[] a = new String[1]; a[0] = Integer.valueOf(1); }});
        t("arraycopy bad", new Case(){public void run() throws Throwable { System.arraycopy(new int[2], 0, new int[2], 1, 2); }});
        t("arraycopy neg", new Case(){public void run() throws Throwable { System.arraycopy(new int[2], -1, new int[2], 0, 1); }});
        t("arraycopy null", new Case(){public void run() throws Throwable { System.arraycopy(null, 0, new int[2], 0, 1); }});
        t("arraycopy type", new Case(){public void run() throws Throwable { System.arraycopy(new int[2], 0, new long[2], 0, 1); }});
        t("arraycopy not array", new Case(){public void run() throws Throwable { System.arraycopy("a", 0, new long[2], 0, 1); }});
        t("npe invoke", new Case(){public void run() throws Throwable { String s = null; s.length(); }});
        t("cce cast", new Case(){public void run() throws Throwable { Object o = "s"; Integer i = (Integer) o; }});
        t("Throwable(null cause)", new Case(){public void run() throws Throwable { System.out.println(new RuntimeException((Throwable) null).getMessage()); }});
        t("Throwable(cause) msg", new Case(){public void run() throws Throwable { System.out.println(new RuntimeException(new Error("e")).getMessage()); }});
        t("Throwable toString", new Case(){public void run() throws Throwable { System.out.println(new IllegalStateException("m") + " " + new Error()); }});
        final int max = Integer.MAX_VALUE;
        // multianewarray: counts left to right, negative first, then an allocated level past the limit.
        t("multi 1,MAX", new Case(){public void run() throws Throwable { Object o = new int[1][max]; }});
        t("multi 0,MAX", new Case(){public void run() throws Throwable { System.out.println(new int[0][max].length); }});
        t("multi MAX,-1", new Case(){public void run() throws Throwable { Object o = new int[max][-1]; }});
        t("multi 0,-1", new Case(){public void run() throws Throwable { Object o = new int[0][-1]; }});
        t("multi -1,MAX", new Case(){public void run() throws Throwable { Object o = new int[-1][max]; }});
        t("multi 2,0,MAX", new Case(){public void run() throws Throwable { System.out.println(new long[2][0][max].length); }});
        // Builder searches see only the used part, not stale chars past it.
        StringBuilder grown = new StringBuilder("abNEEDLE");
        grown.setLength(2);
        StringBuffer cut = new StringBuffer("abNEEDLE");
        cut.delete(2, 8);
        System.out.println("search past length: " + grown.indexOf("NEEDLE") + " " + grown.lastIndexOf("NEEDLE", 9) + " "
                + cut.indexOf("N", 1) + " " + cut.lastIndexOf("E") + " " + grown.indexOf("b") + " " + cut.lastIndexOf("", 9));
        t("multi 0,1,-1", new Case(){public void run() throws Throwable { Object o = new int[0][1][-1]; }});
        System.out.println("empty needle before 0: " + "abc".lastIndexOf("", -5) + " " + new StringBuilder("abc").lastIndexOf("", -5));
        System.out.println("negative chars: " + "a￿b".indexOf(-1) + " " + "a￿b".lastIndexOf(-1) + " " + "a￿b".indexOf(-65537));
        String pair = "x😀y";
        System.out.println("replace inside a pair: " + pair.replace("\ude00", "R").length() + " " + pair.replace("\ud83d", "R").length()
                + " " + "😀\u0000aa".replace("\ude00\u0000", "R").length() + " " + "\ud83d😀".replace("\ud83d", "R").length()
                + " " + "😀\ude00".replace("\ude00", "R").length());
        System.out.println("replace keeps a pair: " + "x😀y".replace("\ud83d", "R").equals("x😀y")
                + " " + "😀x\ude00x\ude00".replace("\ude00x\ude00", "R").equals("😀xR"));
        PrintStream ps = new PrintStream(new OutputStream() {
            public void write(int b) throws IOException { throw new IOException("bad"); }
        });
        ps.println("x");
        System.out.println("checkError: " + ps.checkError());
        PrintStream ok = new PrintStream(new OutputStream() {
            public void write(int b) { }
        });
        ok.println("x");
        System.out.println("checkError ok: " + ok.checkError());
        System.out.println("done");
    }
}
