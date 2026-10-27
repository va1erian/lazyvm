package s1;

public class Arrays1 {
    public static void main(String[] args) {
        byte[] b = new byte[3];
        short[] s = new short[3];
        char[] c = new char[3];
        int[] i = new int[3];
        long[] l = new long[3];
        float[] f = new float[3];
        double[] d = new double[3];
        boolean[] z = new boolean[3];
        String[] str = new String[3];
        System.out.println("defaults: " + b[0] + " " + s[0] + " " + (int) c[0] + " " + i[0] + " " + l[0]
            + " " + f[0] + " " + d[0] + " " + z[0] + " " + str[0]);
        System.out.println("lengths: " + b.length + " " + s.length + " " + c.length + " " + i.length + " "
            + l.length + " " + f.length + " " + d.length + " " + z.length + " " + str.length);

        b[1] = (byte) 200;
        s[1] = (short) 70000;
        c[1] = 'A';
        c[2] = (char) 0xFFFF;
        i[1] = Integer.MIN_VALUE;
        l[1] = Long.MAX_VALUE;
        f[1] = 1.5f;
        d[1] = -2.25;
        z[1] = true;
        str[1] = "hi";
        System.out.println("stored: " + b[1] + " " + s[1] + " " + (int) c[1] + " " + (int) c[2] + " " + i[1]
            + " " + l[1] + " " + f[1] + " " + d[1] + " " + z[1] + " " + z[2] + " " + str[1] + " " + str[2]);
        b[2] = 5;
        b[2] += 300;
        b[2]++;
        s[2] -= 1;
        c[0]++;
        i[2] += 7;
        l[2] -= 7;
        f[2] *= 2;
        d[2] += 0.5;
        System.out.println("updated: " + b[2] + " " + s[2] + " " + (int) c[0] + " " + i[2] + " " + l[2]
            + " " + f[2] + " " + d[2]);
        boolean[] flags = new boolean[5];
        for (int k = 0; k < flags.length; k += 2) flags[k] = true;
        for (int k = 0; k < flags.length; k++) System.out.println("flags[" + k + "] = " + flags[k]);
        char[] hello = {'h', 'e', 'l', 'l', 'o'};
        for (int k = 0; k < hello.length; k++) System.out.println("hello[" + k + "] = " + (int) hello[k]);
        System.out.println(new String(hello));

        try {
            int bad = i.length + args.length;
            i[bad] = 1;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("store oob: " + e.getMessage());
        }
        try {
            System.out.println(str[-1]);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("load oob: " + e.getMessage());
        }
        try {
            long bad = l[100 + args.length];
            System.out.println(bad);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("long oob: " + e.getClass().getName() + " " + e.getMessage());
        }
        try {
            int n = -1 - args.length;
            int[] neg = new int[n];
            System.out.println(neg.length);
        } catch (NegativeArraySizeException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
        try {
            int[] nul = null;
            System.out.println(nul.length);
        } catch (NullPointerException e) {
            System.out.println("null length: NPE");
        }
        try {
            int[] nul = null;
            nul[0] = 1;
        } catch (NullPointerException e) {
            System.out.println("null store: NPE");
        }
        int[] zero = new int[0];
        System.out.println("empty length " + zero.length);

        int[][] grid = new int[3][4];
        for (int r = 0; r < 3; r++)
            for (int k = 0; k < 4; k++) grid[r][k] = r * 10 + k;
        for (int r = 0; r < 3; r++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < grid[r].length; k++) sb.append(grid[r][k]).append(' ');
            System.out.println("row " + r + ": " + sb);
        }
        System.out.println("grid.length " + grid.length + " grid[0].length " + grid[0].length);

        int[][] ragged = new int[4][];
        System.out.println("ragged[0] null? " + (ragged[0] == null));
        for (int r = 0; r < ragged.length; r++) {
            ragged[r] = new int[r];
            for (int k = 0; k < r; k++) ragged[r][k] = r * k;
        }
        for (int r = 0; r < ragged.length; r++) {
            StringBuilder sb = new StringBuilder("[");
            for (int k = 0; k < ragged[r].length; k++) sb.append(ragged[r][k]).append(k + 1 < r ? "," : "");
            System.out.println("ragged " + r + " len " + ragged[r].length + " " + sb + "]");
        }
        int[][] lit = {{1, 2, 3}, {}, {4}};
        System.out.println("lit lens " + lit[0].length + " " + lit[1].length + " " + lit[2].length);
        String[] words = {"alpha", "beta", null, "delta"};
        for (String w : words) System.out.println("word " + w);
        String[][] names = new String[2][2];
        names[1][0] = "x";
        System.out.println(names[0][0] + " " + names[1][0]);
        long[] sums = {1L << 40, -1L};
        System.out.println(sums[0] + sums[1]);
        System.out.println("done");
    }
}
