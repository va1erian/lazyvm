package s2;

public class Interfaces {
    interface Named {
        String PREFIX = "name:";
        int COUNT = compute();
        static int compute() { System.out.println("Named.compute"); return 7; }
        String name();
        default String greet() { return "hello " + name(); }
        static String twice(String s) { return s + s; }
    }

    interface Aged {
        default String greet() { return "age greeting"; }
        int age();
    }

    interface Both extends Named, Aged {
        default String greet() { return Named.super.greet() + " / " + Aged.super.greet(); }
    }

    interface Left { default String who() { return "left"; } }
    interface Right { default String who() { return "right"; } }
    interface Top { default String who() { return "top"; } }
    interface Mid extends Top { }
    interface Over extends Top { default String who() { return "over(" + Top.super.who() + ")"; } }

    static class Person implements Both {
        public String name() { return "bob"; }
        public int age() { return 40; }
    }

    static class Quiet implements Named {
        public String name() { return "quiet"; }
        public String greet() { return "shh " + Named.super.greet(); }
    }

    static class Diamond implements Left, Right {
        public String who() { return Left.super.who() + "+" + Right.super.who(); }
    }

    static class Inherit implements Mid, Over { }
    static class Plain implements Mid { }

    static abstract class Base { public String who() { return "base"; } }
    static class ClassWins extends Base implements Left { }

    interface Visitor { String visit(int x); }

    static String run(Visitor v, int x) { return v.visit(x); }

    public static void main(String[] args) {
        System.out.println("start");
        System.out.println(Named.PREFIX);
        System.out.println("COUNT " + Named.COUNT);
        System.out.println(Named.twice("ab"));
        Person p = new Person();
        System.out.println(p.greet());
        System.out.println(new Quiet().greet());
        Named n = p;
        Aged a = p;
        System.out.println(n.name() + " " + a.age() + " " + n.greet());
        System.out.println(new Diamond().who());
        System.out.println(new Inherit().who());
        System.out.println(new Plain().who());
        System.out.println(new ClassWins().who());
        Object o = p;
        System.out.println((o instanceof Named) + " " + (o instanceof Aged) + " " + (o instanceof Both)
            + " " + (o instanceof Left));
        System.out.println(run(new Visitor() {
            public String visit(int x) { return "visited " + x; }
        }, 9));
        System.out.println(Both.class.isInterface() + " " + Person.class.isInterface());
        System.out.println(Both.class.isAssignableFrom(Person.class) + " " + Person.class.isAssignableFrom(Both.class));
        System.out.println("done");
    }
}
