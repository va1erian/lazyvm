package s2;

public class Dispatch {
    static class A {
        String who() { return "A.who"; }
        String callWho() { return "A.callWho->" + who(); }
        private String priv() { return "A.priv"; }
        String callPriv() { return priv(); }
        static String stat() { return "A.stat"; }
        String callStat() { return stat(); }
        String f(Object o) { return "A.f(Object)"; }
        String f(String s) { return "A.f(String)"; }
        String f(int i) { return "A.f(int)"; }
        String f(long l) { return "A.f(long)"; }
        String f(Integer i) { return "A.f(Integer)"; }
        String g(A a) { return "A.g(A)"; }
    }

    static class B extends A {
        String who() { return "B.who"; }
        String priv() { return "B.priv"; }
        static String stat() { return "B.stat"; }
        String f(Object o) { return "B.f(Object)"; }
        String g(B b) { return "B.g(B)"; }
        String viaSuper() { return super.who() + "|" + super.callWho() + "|" + super.f("s"); }
    }

    static class C extends B {
        String who() { return "C.who"; }
        String viaSuper() { return "C>" + super.viaSuper(); }
    }

    static class Money implements Comparable<Money> {
        final int cents;
        Money(int c) { cents = c; }
        public int compareTo(Money o) { return cents < o.cents ? -1 : cents == o.cents ? 0 : 1; }
        public String toString() { return "$" + cents; }
    }

    static int rawCompare(Comparable c, Object o) { return c.compareTo(o); }

    public static void main(String[] args) {
        A a = new C();
        B b = new C();
        System.out.println(a.who() + " " + a.callWho());
        System.out.println(b.who());
        System.out.println(a.callPriv() + " " + ((B) a).priv());
        System.out.println(a.callStat() + " " + A.stat() + " " + B.stat() + " " + C.stat());
        System.out.println(((B) b).viaSuper());
        System.out.println(((C) a).viaSuper());
        System.out.println(new A().who() + " " + new B().who());

        Object o = "str";
        String s = "str";
        short sh = 1;
        byte by = 2;
        char ch = 'c';
        float fl = 1f;
        System.out.println(a.f(o));
        System.out.println(a.f(s));
        System.out.println(a.f(3));
        System.out.println(a.f(sh));
        System.out.println(a.f(by));
        System.out.println(a.f(ch));
        System.out.println(a.f(4L));
        System.out.println(a.f(Integer.valueOf(5)));
        System.out.println(a.f(fl));
        System.out.println(a.f((Object) s));
        System.out.println(new A().f(o));

        System.out.println(a.g(a));
        System.out.println(a.g(b));
        System.out.println(b.g(b));
        System.out.println(b.g((A) b));
        System.out.println(((B) a).g(new B()));

        Money m1 = new Money(100), m2 = new Money(250);
        System.out.println(m1.compareTo(m2) + " " + m2.compareTo(m1) + " " + m1.compareTo(new Money(100)));
        Comparable<Money> cm = m1;
        System.out.println(cm.compareTo(m2));
        System.out.println(rawCompare(m2, m1));
        try {
            System.out.println(rawCompare(m2, "not money"));
        } catch (ClassCastException e) {
            System.out.println("CCE from bridge");
        }
        int bridges = 0;
        Object x = m1;
        if (x instanceof Comparable) bridges++;
        System.out.println("comparable " + bridges);
        System.out.println("done");
    }
}
