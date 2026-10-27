package s2;

public class StackTraces {
    static void level3(int x) {
        if (x > 0) {
            throw new IllegalArgumentException("level3 failed");
        }
    }

    static void level2(int x) {
        level3(x);
    }

    static void level1(int x) {
        level2(x);
    }

    static class Inner {
        void run() {
            level1(1);
        }
    }

    static void show(Throwable t) {
        System.out.println(t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length; i++) {
            StackTraceElement e = st[i];
            if (e.getClassName().startsWith("s2.")) {
                System.out.println("  " + e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber());
            }
        }
    }

    static void helper() {
        throw new IllegalStateException("boom");
    }

    public static void main(String[] args) {
        try {
            level1(1);
        } catch (IllegalArgumentException e) {
            show(e);
        }
        try {
            new Inner().run();
        } catch (IllegalArgumentException e) {
            show(e);
        }
        Throwable created = new Throwable("created in main");
        show(created);
        try {
            int[] a = new int[1];
            a[args.length + 1] = 0;
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName());
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println("  " + top.getClassName() + "." + top.getMethodName() + ":" + top.getLineNumber());
            System.out.println("  file " + top.getFileName());
        }
        try {
            Object o = null;
            o.hashCode();
        } catch (NullPointerException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println("npe at " + top.getMethodName() + ":" + top.getLineNumber());
        }
        helper();
    }
}
