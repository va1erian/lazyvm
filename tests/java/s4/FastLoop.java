package s4;

/**
 * The interpreter's tight loop for simple instructions must agree with the full path on every
 * edge: wrapping arithmetic, shifts, narrowing, exceptions raised in the middle of a loop (with
 * their line numbers), field kinds, null tests and backward branches that other threads wait on.
 */
public class FastLoop {
    static int sfield = 7;
    static long slong = 1L << 40;
    static volatile boolean flag;
    int ifield = 3;
    Object ofield;

    static void p(String label, Object v) {
        System.out.println(label + " = " + v);
    }

    static void arithmetic() {
        int[] vals = {0, 1, -1, 2, 31, 32, 33, -32, 100, Integer.MAX_VALUE, Integer.MIN_VALUE};
        long sum = 0;
        for (int a : vals) {
            for (int b : vals) {
                sum = sum * 31 + (a + b) + (a - b) * 3 + (a * b) * 5 + (a & b) + (a | b) * 7 + (a ^ b) * 11;
                sum = sum * 31 + (a << b) + (a >> b) * 3 + (a >>> b) * 5 + -a;
                if (b != 0) {
                    sum = sum * 31 + a / b + a % b;
                }
                sum = sum * 31 + (byte) (a + b) + (char) (a - b) * 3 + (short) (a * b) * 5;
            }
        }
        p("arith", sum);
        p("minDiv", Integer.MIN_VALUE / -1 + " " + Integer.MIN_VALUE % -1);
    }

    static void divideByZero() {
        int zero = 0;
        for (int i = 0; i < 3; i++) {
            try {
                p("div", 10 / (zero + i));
            } catch (ArithmeticException e) {
                p("caught", e.getMessage() + " line " + e.getStackTrace()[0].getLineNumber());
            }
            try {
                p("rem", 10 % (zero + i));
            } catch (ArithmeticException e) {
                p("caught", e.getMessage() + " line " + e.getStackTrace()[0].getLineNumber());
            }
        }
    }

    static void arrays() {
        int[] a = new int[4];
        char[] c = new char[4];
        short[] s = new short[4];
        byte[] b = new byte[4];
        boolean[] z = new boolean[4];
        Object[] o = new Object[4];
        for (int i = 0; i < 6; i++) {
            try {
                a[i] = i * 100000;
                c[i] = (char) (i * 70000);
                s[i] = (short) (i * 70000);
                b[i] = (byte) (i * 300);
                z[i] = (i & 1) == 0;
                o[i] = i == 2 ? null : "x" + i;
                p("store", a[i] + " " + (int) c[i] + " " + s[i] + " " + b[i] + " " + z[i] + " " + o[i] + " " + a.length);
            } catch (ArrayIndexOutOfBoundsException e) {
                p("oob", e.getMessage() + " line " + e.getStackTrace()[0].getLineNumber());
            }
        }
        // The loop above throws on its stores first, so loads past the end need their own pass.
        for (int i = -1; i < 6; i += 3) {
            try {
                p("load", a[i] + c[i] + s[i] + b[i] + " " + z[i] + " " + o[i]);
            } catch (ArrayIndexOutOfBoundsException e) {
                p("oobload", e.getMessage() + " line " + e.getStackTrace()[0].getLineNumber());
            }
        }
        int[] none = null;
        try {
            p("null", none[0]);
        } catch (NullPointerException e) {
            p("npe", "line " + e.getStackTrace()[0].getLineNumber());
        }
        try {
            p("nulllen", none.length);
        } catch (NullPointerException e) {
            p("npe", "line " + e.getStackTrace()[0].getLineNumber());
        }
        try {
            Object[] strings = new String[1];
            strings[0] = Integer.valueOf(1);
        } catch (ArrayStoreException e) {
            p("ase", e.getMessage());
        }
    }

    static void fields() {
        FastLoop f = new FastLoop();
        for (int i = 0; i < 3; i++) {
            f.ifield += i;
            sfield = sfield * 3 + f.ifield;
            slong += sfield;
            f.ofield = f.ofield == null ? "set" : null;
        }
        p("fields", f.ifield + " " + sfield + " " + slong + " " + f.ofield);
        FastLoop none = null;
        try {
            none.ifield = 1;
        } catch (NullPointerException e) {
            p("putfield", "line " + e.getStackTrace()[0].getLineNumber());
        }
        try {
            p("getfield", none.ifield);
        } catch (NullPointerException e) {
            p("getfield", "line " + e.getStackTrace()[0].getLineNumber());
        }
    }

    static void references() {
        Object a = "a", b = a, n = null;
        int hits = 0;
        for (int i = 0; i < 4; i++) {
            Object x = i < 2 ? a : n;
            hits += (x == b ? 1 : 0) + (x != b ? 10 : 0) + (x == null ? 100 : 0) + (x != null ? 1000 : 0);
        }
        p("refs", hits);
    }

    /** A class whose static initialiser sleeps, so another thread meets it half initialised. */
    static class Slow {
        static int v;

        static {
            sleep(300);
            v = 42;
        }
    }

    static class SlowWrite {
        static int v;

        static {
            sleep(300);
            v = 42;
        }
    }

    static class Broken {
        static int v;

        static {
            if (sfield >= 0) {
                throw new IllegalStateException("broken");
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static int readSlow() {
        return Slow.v;
    }

    static void writeSlow(int x) {
        SlowWrite.v = x;
    }

    static int readBroken() {
        return Broken.v;
    }

    static void writeBroken(int x) {
        Broken.v = x;
    }

    /** Static reads and writes through an already resolved field must still wait for, or fail with, class initialisation. */
    static void initStates() throws InterruptedException {
        int[] seen = new int[2];
        Thread first = new Thread(() -> seen[0] = readSlow());
        Thread second = new Thread(() -> {
            sleep(100);
            seen[1] = readSlow();
        });
        first.start();
        second.start();
        first.join();
        second.join();
        p("slow read", seen[0] + " " + seen[1]);
        // The reader that starts the initialisation resolves the field; the writer then finds it resolved.
        Thread starter = new Thread(() -> writeSlow(1));
        Thread writer = new Thread(() -> {
            sleep(100);
            writeSlow(7);
        });
        starter.start();
        writer.start();
        starter.join();
        writer.join();
        p("slow write", SlowWrite.v);
        for (int i = 0; i < 3; i++) {
            try {
                p("broken read", readBroken());
            } catch (Throwable t) {
                p("broken read", t.getClass().getName());
            }
            try {
                writeBroken(i);
                p("broken write", "returned");
            } catch (Throwable t) {
                p("broken write", t.getClass().getName());
            }
        }
    }

    static void spin() throws InterruptedException {
        Thread t = new Thread(() -> {
            long n = 0;
            while (n < 200000) {
                n++;
            }
            flag = true;
        });
        t.start();
        int waits = 0;
        while (!flag) {
            waits++;
        }
        t.join();
        p("spin", "released " + (waits >= 0));
    }

    public static void main(String[] args) throws Exception {
        arithmetic();
        divideByZero();
        arrays();
        fields();
        references();
        initStates();
        spin();
    }
}
