package s2;

public class Inheritance {
    static abstract class Shape {
        protected String name = "shape";
        int sides = 0;
        Shape() {
            System.out.println("Shape() describe=" + describe());
        }
        Shape(String n) {
            this();
            name = n;
            System.out.println("Shape(String) " + n);
        }
        abstract double area();
        String describe() { return "Shape[" + name + "]"; }
        public String toString() { return describe() + " area=" + area(); }
        protected String secret() { return "shape-secret"; }
    }

    static class Rect extends Shape {
        int w, h = 3;
        String name = "rect-field";
        int sides = 4;
        Rect(int w) {
            super("rect");
            this.w = w;
            System.out.println("Rect(int) w=" + w + " h=" + h);
        }
        double area() { return w * h; }
        String describe() { return "Rect[" + super.name + "," + name + "," + w + "x" + h + "]"; }
        String parentDescribe() { return super.describe(); }
        protected String secret() { return "rect+" + super.secret(); }
    }

    static class Square extends Rect {
        Square(int s) {
            super(s);
            System.out.println("Square(int)");
        }
        double area() { return super.area() + 0.5; }
    }

    static class Counter {
        int n;
        Counter() { this(10); System.out.println("Counter()"); }
        Counter(int n) { this.n = n; System.out.println("Counter(int) " + n); }
    }

    static class Sub extends Counter {
        Sub() { super(); System.out.println("Sub() n=" + n); }
    }

    public static void main(String[] args) {
        Rect r = new Rect(5);
        System.out.println(r);
        System.out.println(r.parentDescribe());
        System.out.println("fields: " + r.name + " " + r.sides + " " + ((Shape) r).name + " " + ((Shape) r).sides);
        Shape s = r;
        System.out.println("via Shape: " + s.name + " " + s.sides + " " + s.describe());
        System.out.println(r.secret());
        System.out.println("--");
        Shape sq = new Square(2);
        System.out.println(sq);
        System.out.println("area " + sq.area());
        System.out.println("--");
        new Sub();
        Shape anon = new Shape("anon") {
            double area() { return 1.5; }
            String describe() { return "Anon[" + name + "]"; }
        };
        System.out.println(anon);
        System.out.println(anon.getClass().getSuperclass().getName());
        System.out.println(sq.getClass().getName() + " " + sq.getClass().getSuperclass().getName());
        System.out.println("done");
    }
}
