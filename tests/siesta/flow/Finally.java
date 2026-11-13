/** The Java mirror of Finally.sie: it must print the same. */
public class Finally {
    static StringBuilder log = new StringBuilder();

    static void note(String s) { log.append(s).append(' '); }

    static int fromTry() {
        try { note("try"); return 1; } finally { note("fin"); }
    }

    static int fromCatch() {
        try { throw new IllegalStateException("x"); }
        catch (IllegalStateException e) { note("catch"); return 2; }
        finally { note("fin"); }
    }

    @SuppressWarnings("finally")
    static int fromFinally() {
        try { note("try"); return 1; } finally { note("fin"); return 3; }
    }

    static int computedFirst() {
        int x = 10;
        try { return x; } finally { x = 20; note("x=" + x); }
    }

    static int loops() {
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            try {
                if (i == 1) continue;
                if (i == 3) break;
                sum += i;
            } finally {
                note("f" + i);
            }
        }
        int j = 0;
        while (true) {
            try {
                try { j++; if (j == 2) break; }
                catch (RuntimeException e) { note("never"); }
                finally { note("in" + j); }
            } finally {
                note("out" + j);
            }
        }
        return sum * 10 + j;
    }

    @SuppressWarnings("finally")
    static String throws_(int where) {
        try {
            try {
                if (where == 0) throw new IllegalArgumentException("try");
                return "none";
            } catch (IllegalArgumentException e) {
                if (where == 0) throw new IllegalStateException("catch");
                return "unreached";
            } finally {
                note("fin" + where);
                if (where == 1) throw new UnsupportedOperationException("finally");
            }
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    @SuppressWarnings("finally")
    static String swallowed() {
        for (int i = 0; i < 2; i++) {
            try { throw new RuntimeException("lost"); }
            finally { note("drop" + i); continue; }
        }
        return "loop ended";
    }

    public static void main(String[] args) {
        System.out.println(fromTry() + " " + fromCatch() + " " + fromFinally() + " " + computedFirst());
        System.out.println(log);
        log.setLength(0);
        System.out.println(loops());
        System.out.println(log);
        log.setLength(0);
        System.out.println(throws_(0) + " | " + throws_(1) + " | " + throws_(2));
        System.out.println(log);
        System.out.println(swallowed() + " " + log);
    }
}
