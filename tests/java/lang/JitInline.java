package lang;

/**
 * Calls LazyVM's JIT inlines: small static, private, constructor and
 * single-receiver virtual methods. Each scenario runs often enough for its
 * method to be compiled with the calls inlined (the call sites resolved by
 * then), then once more where the inlined body cannot go on (it throws, or
 * its receiver is of another class). The frames it stands for are then
 * built and the interpreter runs on: stack traces, side effects made before
 * the throw, and results must be as if nothing was inlined.
 */
public class JitInline {
    static int counter;
    int field = 3;
    int[] cells = new int[4];

    static int twice(int x) { return x * 2; }
    static int divide(int a, int b) { return a / b; }
    static long wide(long a, double b) { return a + (long) b; }
    static int nested(int x) { return twice(x) + 1; }
    static int calls(int x) { return Integer.valueOf(x).hashCode() + 1; }
    static int cell(int[] a, int i) { return a[i]; }

    private int priv(int x) { return field + x; }
    int getField() { return field; }
    void setField(int v) { field = v; }
    int store(int i, int v) { cells[i] = v; counter += v; return cells[i]; }

    abstract static class Shape { abstract int area(); int twiceArea() { return area() * 2; } }
    static final class Sq extends Shape { final int s; Sq(int s) { this.s = s; } int area() { return s * s; } }
    static final class Rect extends Shape {
        final int w;
        final int h;
        Rect(int w, int h) { this.w = w; this.h = h; }
        int area() { return w * h; }
    }

    static String where(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 3; i++) {
            sb.append(" < ").append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    static long statics(int i, int divisor) {
        return twice(i) + nested(i) + calls(i) + wide(i, 0.5 * i) + divide(i, divisor);
    }

    static int cells(int[] a, int i) {
        return cell(a, i) + twice(i);
    }

    static int instance(JitInline o, int i, int at) {
        o.setField(o.getField() + o.priv(i) & 1023);
        return o.store(at, i);
    }

    static int shape(Shape s) {
        return s.area() + s.twiceArea();
    }

    public static void main(String[] args) {
        long sum = 0;
        for (int i = 0; i < 300; i++) {
            sum += statics(i, 7);
        }
        System.out.println("statics " + sum);
        try {
            System.out.println(statics(5, 0));
        } catch (ArithmeticException e) {
            System.out.println(where(e));
        }
        int[] four = new int[4];
        for (int i = 0; i < 300; i++) {
            sum += cells(four, i & 3);
        }
        try {
            System.out.println(cells(four, 9));
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println(where(e));
        }
        try {
            System.out.println(cells(null, 0));
        } catch (NullPointerException e) {
            System.out.println(where(e));
        }
        JitInline o = new JitInline();
        int acc = 0;
        counter = 0;
        for (int i = 0; i < 300; i++) {
            acc += instance(o, i, i & 3);
        }
        System.out.println("instance " + o.field + " " + acc + " " + counter);
        // The store into cells throws before counter changes: it stays.
        try {
            instance(o, 5, 9);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println(where(e) + " counter " + counter + " field " + o.field);
        }
        try {
            instance(null, 1, 0);
        } catch (NullPointerException e) {
            System.out.println("npe " + where(e).split(" < ")[1]);
        }
        // One receiver class many times, then another: the guard fails.
        Shape sq = new Sq(3);
        int total = 0;
        for (int i = 0; i < 300; i++) {
            total += shape(sq);
        }
        System.out.println("virtual " + total + " " + shape(new Rect(2, 7)) + " " + shape(new Sq(4)));
    }
}
