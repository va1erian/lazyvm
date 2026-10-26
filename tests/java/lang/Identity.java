package lang;

public class Identity {
    static class Plain { }

    static class Overrider {
        public int hashCode() { return 12345; }
    }

    static void p(String label, Object v) { System.out.println(label + " = " + v); }

    public static void main(String[] args) {
        Plain a = new Plain();
        Plain b = new Plain();
        p("identityHashCode == hashCode", System.identityHashCode(a) == a.hashCode());
        p("same object same hash", a.hashCode() == a.hashCode());
        p("identityHashCode stable", System.identityHashCode(a) == System.identityHashCode(a));
        p("a == b", a == b);
        p("a == a", a == a);
        p("a.equals(b)", a.equals(b));
        p("a.equals(a)", a.equals(a));
        p("null identityHashCode", System.identityHashCode(null));
        Overrider o = new Overrider();
        p("override hashCode", o.hashCode());
        p("identity of override stable", System.identityHashCode(o) == System.identityHashCode(o));
        String s = "text";
        p("String hashCode", s.hashCode());

        int first = a.hashCode();
        int firstB = b.hashCode();
        Object[] keep = new Object[1000];
        for (int i = 0; i < 200000; i++) {
            Object junk = new Object[(i % 7) + 1];
            if (i % 200 == 0) keep[i / 200] = junk;
        }
        System.gc();
        for (int i = 0; i < 200000; i++) {
            Object junk = new int[(i % 5) + 1];
            if (i % 400 == 0) keep[i / 400] = junk;
        }
        System.gc();
        p("hash stable after gc", a.hashCode() == first);
        p("hash b stable after gc", b.hashCode() == firstB);
        p("identity stable after gc", System.identityHashCode(a) == first);
        boolean allSame = true;
        int[] hashes = new int[keep.length];
        for (int i = 0; i < keep.length; i++) if (keep[i] != null) hashes[i] = keep[i].hashCode();
        System.gc();
        for (int i = 0; i < keep.length; i++) if (keep[i] != null && keep[i].hashCode() != hashes[i]) allSame = false;
        p("array hashes stable after gc", allSame);
        Plain[] many = new Plain[300];
        int[] manyHash = new int[many.length];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Plain();
            manyHash[i] = many[i].hashCode();
        }
        System.gc();
        boolean stable = true;
        for (int i = 0; i < many.length; i++) if (many[i].hashCode() != manyHash[i]) stable = false;
        p("300 objects stable", stable);
        System.out.println("done");
    }
}
