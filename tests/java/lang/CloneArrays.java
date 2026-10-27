package lang;

public class CloneArrays {
    static class Point implements Cloneable {
        int x;
        int[] data = {1, 2, 3};
        Point(int x) { this.x = x; }
        public Point clone() {
            try {
                return (Point) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("cannot happen");
            }
        }
        public String toString() { return "Point(" + x + "," + data[0] + ")"; }
    }

    static class Deep extends Point {
        String tag = "deep";
        Deep(int x) { super(x); }
        public Deep clone() {
            Deep d = (Deep) super.clone();
            d.data = data.clone();
            return d;
        }
    }

    static class NotCloneable {
        int v = 1;
        Object tryClone() throws CloneNotSupportedException { return super.clone(); }
    }

    static class Sub extends NotCloneable { }

    public static void main(String[] args) throws Exception {
        int[] a = {1, 2, 3};
        int[] ac = a.clone();
        ac[1] = 20;
        System.out.println(a[1] + " " + ac[1] + " " + (a != ac) + " " + ac.length + " " + ac.getClass().getName());
        long[] la = {Long.MAX_VALUE};
        System.out.println(la.clone()[0]);
        double[] da = {1.5};
        System.out.println(da.clone()[0]);
        boolean[] za = {true, false};
        System.out.println(za.clone()[0] + " " + za.clone()[1]);
        Object[] oa = {"x", 1, null};
        Object[] oc = oa.clone();
        System.out.println(oc.length + " " + oc[0] + " " + oc[1] + " " + oc[2] + " " + (oa[0] == oc[0]));
        int[][] grid = {{1, 2}, {3, 4}};
        int[][] gc = grid.clone();
        gc[0][0] = 99;
        System.out.println("shared rows: " + grid[0][0] + " " + (grid[0] == gc[0]));
        gc[1] = new int[]{0};
        System.out.println("independent slots: " + grid[1].length + " " + gc[1].length);
        int[][] deep = new int[grid.length][];
        for (int i = 0; i < grid.length; i++) deep[i] = grid[i].clone();
        deep[1][1] = -1;
        System.out.println("deep copy: " + grid[1][1] + " " + deep[1][1]);
        System.out.println(gc.getClass().getName());
        String[] sa = {"a", "b"};
        Object[] saObj = sa;
        Object cloned = saObj.clone();
        System.out.println(cloned.getClass().getName() + " " + (cloned instanceof String[]));

        Point p = new Point(5);
        Point q = p.clone();
        q.x = 6;
        q.data[0] = 100;
        System.out.println(p + " " + q + " " + (p != q) + " " + (p.data == q.data) + " " + (p.getClass() == q.getClass()));
        Deep d = new Deep(1);
        Deep e = d.clone();
        e.data[0] = 7;
        e.tag = "copy";
        System.out.println(d + " " + d.tag + " | " + e + " " + e.tag + " " + e.getClass().getName());
        Point viaBase = d;
        Point baseClone = viaBase.clone();
        System.out.println(baseClone.getClass().getName());

        try {
            new NotCloneable().tryClone();
            System.out.println("unexpected: cloned");
        } catch (CloneNotSupportedException ex) {
            System.out.println("CloneNotSupportedException " + ex.getClass().getName() + " " + ex.getMessage());
        }
        try {
            new Sub().tryClone();
            System.out.println("unexpected: cloned");
        } catch (CloneNotSupportedException ex) {
            System.out.println("CloneNotSupportedException again");
        }
        Cloneable c = new Point(1);
        System.out.println((c instanceof Point) + " " + (new int[0] instanceof Cloneable));
        System.out.println("done");
    }
}
