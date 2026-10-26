package s2;

import java.util.Objects;

public class Objects2 {
    static class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        public int hashCode() { return 31 * x + y; }
        public String toString() { return "(" + x + "," + y + ")"; }
    }

    static class Named {
        final String name;
        final int n;
        Named(String name, int n) { this.name = name; this.n = n; }
        public boolean equals(Object o) {
            return o instanceof Named && Objects.equals(name, ((Named) o).name) && n == ((Named) o).n;
        }
        public int hashCode() { return Objects.hash(name, n); }
        public String toString() { return "Named{" + name + "," + n + "}"; }
    }

    static class Plain { }

    static class Wrapper {
        Object inner;
        Wrapper(Object inner) { this.inner = inner; }
        public String toString() { return "W<" + inner + ">"; }
    }

    public static void main(String[] args) {
        Point a = new Point(1, 2), b = new Point(1, 2), c = new Point(2, 1);
        System.out.println(a + " " + b + " " + c);
        System.out.println("a.equals(b) " + a.equals(b));
        System.out.println("a == b " + (a == b));
        System.out.println("a.equals(c) " + a.equals(c));
        System.out.println("a.equals(null) " + a.equals(null));
        System.out.println("a.equals(string) " + a.equals("x"));
        System.out.println("hash " + a.hashCode() + " " + b.hashCode() + " " + c.hashCode());
        System.out.println("Objects.equals " + Objects.equals(a, b) + " " + Objects.equals(a, c)
            + " " + Objects.equals(null, null) + " " + Objects.equals(a, null) + " " + Objects.equals(null, a));
        System.out.println("Objects.hashCode " + Objects.hashCode(a) + " " + Objects.hashCode(null));
        System.out.println("Objects.toString " + Objects.toString(a) + " " + Objects.toString(null)
            + " " + Objects.toString(null, "dflt"));

        Named n1 = new Named("x", 1), n2 = new Named("x", 1), n3 = new Named(null, 1);
        System.out.println(n1 + " " + n3);
        System.out.println(n1.equals(n2) + " " + n1.equals(n3) + " " + n3.equals(new Named(null, 1)));
        System.out.println("Objects.hash(1,2,3) = " + Objects.hash(1, 2, 3));
        System.out.println("Objects.hash() = " + Objects.hash());
        System.out.println("Objects.hash(null) = " + Objects.hash((Object[]) null));
        System.out.println("Objects.hash(\"x\",1) = " + Objects.hash("x", 1));
        System.out.println("n1.hashCode == n2.hashCode " + (n1.hashCode() == n2.hashCode()));
        System.out.println("n3.hashCode " + n3.hashCode());

        Plain p1 = new Plain(), p2 = new Plain();
        System.out.println("Plain equals self " + p1.equals(p1));
        System.out.println("Plain equals other " + p1.equals(p2));
        System.out.println("Plain hash stable " + (p1.hashCode() == p1.hashCode()));
        System.out.println("Plain toString shape " + p1.toString().startsWith("s2.Objects2$Plain@"));
        System.out.println(new Wrapper(a) + " " + new Wrapper(null) + " " + new Wrapper(new Wrapper("s")));
        Object[] arr = {a, "s", 3, null};
        for (Object o : arr) System.out.println("elem " + o + " " + Objects.isNull(o) + " " + Objects.nonNull(o));
        try {
            Objects.requireNonNull(null);
        } catch (NullPointerException e) {
            System.out.println("requireNonNull: " + e.getMessage());
        }
        try {
            Objects.requireNonNull(null, "must not be null");
        } catch (NullPointerException e) {
            System.out.println("requireNonNull: " + e.getMessage());
        }
        System.out.println(Objects.requireNonNull("fine"));
        System.out.println("done");
    }
}
