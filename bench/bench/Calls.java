package bench;

/** Calls and field access: virtual, interface and static calls, getters and setters, recursion. */
public final class Calls {
    interface Shape {
        int area();
    }

    abstract static class Base implements Shape {
        protected int w;
        protected int h;

        abstract int perimeter();

        int getW() {
            return w;
        }

        void setW(int v) {
            w = v;
        }
    }

    static final class Rect extends Base {
        Rect(int w, int h) {
            this.w = w;
            this.h = h;
        }

        public int area() {
            return w * h;
        }

        int perimeter() {
            return 2 * (w + h);
        }
    }

    static final class Square extends Base {
        Square(int s) {
            w = s;
            h = s;
        }

        public int area() {
            return w * w;
        }

        int perimeter() {
            return 4 * w;
        }
    }

    private static int counter;

    private Calls() {
    }

    public static void main(String[] args) {
        int rounds = 4 * Report.scale(args);
        Base[] shapes = new Base[64];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = (i & 1) == 0 ? new Rect(i + 1, i + 2) : new Square(i + 3);
        }
        long start = System.nanoTime();
        long sum = 0;
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 4000; k++) {
                for (int i = 0; i < shapes.length; i++) {
                    Base b = shapes[i];
                    Shape s = b;
                    sum += s.area() + b.perimeter() + b.getW();
                    b.setW(b.getW() + 1 & 63);
                    counter += step(i);
                }
            }
            sum += fib(24);
        }
        Report.print("calls", sum + counter, start);
    }

    private static int step(int i) {
        return (i ^ counter) & 3;
    }

    private static int fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }
}
