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
