package s2;

public class Exceptions {
    static class AppException extends Exception {
        final int code;
        AppException(String m, int code) { super(m); this.code = code; }
        AppException(String m, Throwable cause) { super(m, cause); code = -1; }
    }
    static class DeepException extends AppException {
        DeepException(String m) { super(m, 99); }
    }
    static class BadState extends RuntimeException {
        BadState() { super(); }
        BadState(Throwable cause) { super(cause); }
    }

    static class Res implements AutoCloseable {
        final String name;
        final boolean failClose;
        Res(String name, boolean failClose) { this.name = name; this.failClose = failClose; System.out.println("open " + name); }
        public void close() throws Exception {
            System.out.println("close " + name);
            if (failClose) throw new IllegalStateException("close-" + name);
        }
    }

    static void thrower(int k) throws AppException {
        if (k == 0) throw new AppException("plain", 7);
        if (k == 1) throw new DeepException("deep");
        if (k == 2) throw new BadState();
        if (k == 3) throw new ArithmeticException("arith");
        if (k == 4) { Object o = null; o.toString(); }
        if (k == 5) { int[] a = new int[1]; a[k] = 1; }
        System.out.println("no throw for " + k);
    }

    static void multi(int k) {
        try {
            thrower(k);
        } catch (DeepException e) {
            System.out.println("deep branch " + e.code);
        } catch (AppException e) {
            System.out.println("app branch " + e.getMessage() + " code " + e.code);
        } catch (ArithmeticException | BadState e) {
            System.out.println("multi branch " + e.getClass().getName());
        } catch (RuntimeException e) {
            System.out.println("runtime branch " + e.getClass().getName());
        }
    }

    static void rethrow() throws AppException {
        try {
            thrower(0);
        } catch (AppException e) {
            System.out.println("rethrowing " + e.getMessage());
            throw e;
        }
    }

    static String nested() {
        StringBuilder sb = new StringBuilder();
        try {
            try {
                throw new IllegalArgumentException("inner");
            } catch (IllegalStateException e) {
                sb.append("wrong catch;");
            } finally {
                sb.append("inner finally;");
            }
        } catch (IllegalArgumentException e) {
            sb.append("outer caught ").append(e.getMessage()).append(';');
        }
        try {
            try {
                throw new IllegalArgumentException("first");
            } catch (IllegalArgumentException e) {
                sb.append("in catch;");
                throw new IllegalStateException("second");
            }
        } catch (IllegalStateException e) {
            sb.append("caught ").append(e.getMessage());
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        for (int k = 0; k <= 6; k++) multi(k);
        try {
            rethrow();
        } catch (AppException e) {
            System.out.println("main got " + e);
        }
        System.out.println(nested());

        Exception e1 = new Exception();
        Exception e2 = new Exception("msg");
        Exception e3 = new Exception("wrap", e2);
        Exception e4 = new Exception(e2);
        Exception e5 = new RuntimeException((String) null);
        System.out.println(e1.getMessage() + " | " + e1 + " | " + e1.getCause());
        System.out.println(e2.getMessage() + " | " + e2);
        System.out.println(e3.getMessage() + " | " + e3 + " | " + (e3.getCause() == e2));
        System.out.println(e4.getMessage() + " | " + e4 + " | " + (e4.getCause() == e2));
        System.out.println(e5.getMessage() + " | " + e5);
        System.out.println(new BadState(new AppException("x", 1)).getMessage());
        System.out.println(new AppException("c", e2).getCause().getMessage());

        Exception e6 = new Exception("late cause");
        e6.initCause(e2);
        System.out.println("initCause -> " + e6.getCause().getMessage());
        try {
            e6.initCause(e1);
        } catch (IllegalStateException e) {
            System.out.println("second initCause: IllegalStateException");
        }
        try {
            e2.initCause(e2);
        } catch (IllegalArgumentException e) {
            System.out.println("self cause: IllegalArgumentException");
        }
        try {
            throw new NullPointerException("custom npe");
        } catch (NullPointerException e) {
            System.out.println(e);
        }

        try (Res a = new Res("a", false); Res b = new Res("b", true)) {
            System.out.println("body");
            throw new AppException("body failed", 3);
        } catch (AppException e) {
            System.out.println("caught " + e.getMessage() + " suppressed " + e.getSuppressed().length);
            for (Throwable t : e.getSuppressed()) System.out.println("  suppressed: " + t);
        }
        try (Res c = new Res("c", true)) {
            System.out.println("body ok");
        } catch (IllegalStateException e) {
            System.out.println("close threw " + e.getMessage() + " suppressed " + e.getSuppressed().length);
        }
        try (Res d = new Res("d", true); Res f = new Res("f", true)) {
            throw new BadState();
        } catch (BadState e) {
            System.out.println("suppressed count " + e.getSuppressed().length);
            for (Throwable t : e.getSuppressed()) System.out.println("  suppressed: " + t.getMessage());
        }
        Throwable t = new Throwable("t");
        t.addSuppressed(new Error("manual"));
        System.out.println(t.getSuppressed()[0]);
        try {
            t.addSuppressed(t);
        } catch (IllegalArgumentException e) {
            System.out.println("self suppress rejected");
        }
        try {
            throw new StackOverflowError();
        } catch (Error e) {
            System.out.println(e.getClass().getName() + " is VirtualMachineError "
                + (e instanceof VirtualMachineError));
        }
        System.out.println("done");
    }
}
