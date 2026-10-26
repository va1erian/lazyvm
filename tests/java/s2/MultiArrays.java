package s2;

public class MultiArrays {
    static class Box implements Cloneable {
        int v;
        Box(int v) { this.v = v; }
        public String toString() { return "Box" + v; }
    }

    public static void main(String[] args) {
        int[][] a = new int[2][3];
        System.out.println(a.length + " " + a[0].length + " " + a[1].length + " " + a[1][2]);
        a[1][2] = 7;
        System.out.println(a[1][2] + " " + a[0][2]);
        System.out.println("rows distinct: " + (a[0] != a[1]));

        int[][][] c = new int[2][3][4];
        c[1][2][3] = 9;
        int total = 0;
        for (int[][] plane : c)
            for (int[] row : plane)
                for (int v : row) total += v + 1;
        System.out.println("3d " + c.length + " " + c[0].length + " " + c[0][0].length + " total " + total);

        long[][] l = new long[2][2];
        l[0][1] = Long.MIN_VALUE;
        System.out.println(l[0][0] + " " + l[0][1]);
        String[][] s = new String[2][3];
        System.out.println(s[1][2] + " " + s.length + " " + s[0].length);
        s[0][0] = "z";
        System.out.println(s[0][0]);
        double[][] d = new double[1][2];
        boolean[][] z = new boolean[1][1];
        char[][] ch = new char[2][1];
        System.out.println(d[0][1] + " " + z[0][0] + " " + (int) ch[1][0]);

        int[][] part = new int[2][];
        System.out.println(part[0] + " " + part[1] + " " + part.length);
        part[0] = new int[5];
        System.out.println(part[0].length);
        int[][][] part3 = new int[2][3][];
        System.out.println(part3.length + " " + part3[1].length + " " + part3[1][2]);
        String[][][] part3s = new String[2][][];
        System.out.println(part3s[0]);

        int[][] zeroDim = new int[0][5];
        System.out.println("zero outer " + zeroDim.length);
        int[][] zeroInner = new int[3][0];
        System.out.println("zero inner " + zeroInner[2].length);
        try {
            int n = -1 - args.length;
            int[][] bad = new int[2][n];
            System.out.println(bad.length);
        } catch (NegativeArraySizeException e) {
            System.out.println("negative inner: " + e.getClass().getName());
        }
        try {
            int n = -1 - args.length;
            int[][] bad = new int[n][2];
            System.out.println(bad.length);
        } catch (NegativeArraySizeException e) {
            System.out.println("negative outer: " + e.getClass().getName());
        }
        System.out.println(a.getClass().getName() + " " + c.getClass().getName() + " " + s.getClass().getName());

        int[] orig = {1, 2, 3};
        int[] copy = orig.clone();
        copy[0] = 100;
        System.out.println(orig[0] + " " + copy[0] + " " + (orig != copy) + " " + copy.length);
        System.out.println(copy.getClass().getName());

        int[][] grid = {{1, 2}, {3, 4}};
        int[][] gc = grid.clone();
        gc[0][0] = 50;
        System.out.println("shallow: " + grid[0][0] + " same row " + (grid[1] == gc[1]));
        gc[1] = new int[]{8, 9};
        System.out.println("replaced row: " + grid[1][0] + " " + gc[1][0]);

        Box[] boxes = {new Box(1), new Box(2)};
        Box[] bc = boxes.clone();
        bc[0].v = 77;
        System.out.println(boxes[0] + " " + (boxes[0] == bc[0]) + " " + bc.getClass().getName());
        Object[] objs = new String[]{"p", "q"};
        Object[] oc = objs.clone();
        System.out.println(oc.getClass().getName() + " " + oc[1]);
        try {
            oc[0] = new Object();
        } catch (ArrayStoreException e) {
            System.out.println("clone keeps String[] type");
        }
        char[] cc = "abc".toCharArray().clone();
        System.out.println(new String(cc));
        int[] empty = new int[0].clone();
        System.out.println(empty.length);
        System.out.println("done");
    }
}
