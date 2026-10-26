package s4;

import java.lang.reflect.Array;
import java.util.Arrays;

/** java.lang.reflect.Array and the typed overloads of Arrays.copyOf. */
public class ReflectArrays {
    interface Run { Object run() throws Exception; }

    static void show(String what, Run r) {
        String s;
        try {
            Object v = r.run();
            s = v == null ? "null" : v + " (" + v.getClass().getSimpleName() + ")";
        } catch (Exception e) {
            s = e.getClass().getName() + ": " + e.getMessage();
        }
        System.out.println(what + " -> " + s);
    }

    public static void main(String[] args) {
        Object ia = Array.newInstance(int.class, 3);
        System.out.println(ia.getClass().getName() + " " + Array.getLength(ia));
        Object sa = Array.newInstance(String.class, 2);
        System.out.println(sa.getClass().getName() + " " + Array.getLength(sa) + " " + (sa instanceof String[]));
        Object empty = Array.newInstance(long.class, 0);
        System.out.println(empty.getClass().getName() + " " + Array.getLength(empty));
        Object nested = Array.newInstance(int[].class, 2);
        System.out.println(nested.getClass().getName() + " " + (((Object[]) nested)[0] == null));
        int[][][] cube = (int[][][]) Array.newInstance(int.class, 2, 3, 4);
        System.out.println(cube.getClass().getName() + " " + cube.length + " " + cube[1].length + " " + cube[1][2].length
                + " " + cube[1][2][3]);
        Object[][] grid = (Object[][]) Array.newInstance(String.class, 2, 2);
        System.out.println(grid.getClass().getName() + " " + (grid instanceof String[][]) + " " + grid[1][1]);
        Object ragged = Array.newInstance(int.class, new int[] {2, 0});
        System.out.println(ragged.getClass().getName() + " " + ((int[][]) ragged)[1].length);
        Object one = Array.newInstance(char.class, new int[] {3});
        System.out.println(one.getClass().getName());
        show("negative", () -> Array.newInstance(int.class, -1));
        show("negative dim", () -> Array.newInstance(int.class, 2, -1));
        show("void", () -> Array.newInstance(void.class, 1));
        show("empty dims", () -> Array.newInstance(int.class, new int[0]));
        show("null type", () -> Array.newInstance(null, 1));

        int[] ints = {10, 20, 30};
        show("get", () -> Array.get(ints, 1));
        show("getInt", () -> Array.getInt(ints, 2));
        show("getLong from int", () -> Array.getLong(ints, 0));
        show("getDouble from int", () -> Array.getDouble(ints, 0));
        show("getShort from int", () -> Array.getShort(ints, 0));
        show("getBoolean from int", () -> Array.getBoolean(ints, 0));
        show("get out of range", () -> Array.get(ints, 3));
        show("get negative", () -> Array.get(ints, -1));
        show("get non-array", () -> Array.get("s", 0));
        show("get null", () -> Array.get(null, 0));
        show("getLength non-array", () -> Array.getLength("s"));
        show("getLength null", () -> Array.getLength(null));
        show("getInt object array", () -> Array.getInt(new String[1], 0));
        show("set", () -> { Array.set(ints, 0, 99); return ints[0]; });
        show("set short", () -> { Array.set(ints, 0, (short) 5); return ints[0]; });
        show("set char", () -> { Array.set(ints, 0, 'A'); return ints[0]; });
        show("set long", () -> { Array.set(ints, 0, 5L); return null; });
        show("set string", () -> { Array.set(ints, 0, "x"); return null; });
        show("set null prim", () -> { Array.set(ints, 0, null); return null; });
        show("setInt", () -> { Array.setInt(ints, 1, 7); return ints[1]; });
        show("setByte", () -> { Array.setByte(ints, 1, (byte) -2); return ints[1]; });
        show("setLong on int", () -> { Array.setLong(ints, 1, 7L); return null; });
        show("setInt on object", () -> { Array.setInt(new String[1], 0, 1); return null; });
        show("set out of range", () -> { Array.set(ints, 5, 1); return null; });

        long[] longs = new long[2];
        double[] doubles = new double[2];
        float[] floats = new float[1];
        char[] chars = new char[1];
        boolean[] bools = new boolean[1];
        byte[] bytes = new byte[1];
        short[] shorts = new short[1];
        show("setInt long[]", () -> { Array.setInt(longs, 0, 3); return longs[0]; });
        show("setChar long[]", () -> { Array.setChar(longs, 1, 'z'); return longs[1]; });
        show("setLong double[]", () -> { Array.setLong(doubles, 0, 4L); return doubles[0]; });
        show("setFloat double[]", () -> { Array.setFloat(doubles, 1, 1.5f); return doubles[1]; });
        show("setLong float[]", () -> { Array.setLong(floats, 0, 4L); return floats[0]; });
        show("setDouble float[]", () -> { Array.setDouble(floats, 0, 4.0); return null; });
        show("setChar char[]", () -> { Array.setChar(chars, 0, 'k'); return chars[0]; });
        show("setInt char[]", () -> { Array.setInt(chars, 0, 107); return null; });
        show("setBoolean", () -> { Array.setBoolean(bools, 0, true); return Array.getBoolean(bools, 0); });
        show("setInt boolean[]", () -> { Array.setInt(bools, 0, 1); return null; });
        show("setByte short[]", () -> { Array.setByte(shorts, 0, (byte) 9); return Array.getShort(shorts, 0); });
        show("getByte byte[]", () -> Array.getByte(bytes, 0));
        show("getChar char[]", () -> Array.getChar(chars, 0));
        show("getFloat long[]", () -> Array.getFloat(longs, 0));
        show("getInt long[]", () -> Array.getInt(longs, 0));
        show("getDouble float[]", () -> Array.getDouble(floats, 0));

        String[] strs = new String[2];
        show("set string[]", () -> { Array.set(strs, 0, "a"); return strs[0]; });
        show("set string[] wrong", () -> { Array.set(strs, 1, 1); return null; });
        show("set string[] null", () -> { Array.set(strs, 0, null); return strs[0]; });
        Object[] objs = new Number[1];
        show("set Number[]", () -> { Array.set(objs, 0, 3); return objs[0]; });
        show("set Number[] wrong", () -> { Array.set(objs, 0, "s"); return null; });
        show("get Number[]", () -> Array.get(objs, 0));
        int[][] two = new int[2][];
        show("set int[][]", () -> { Array.set(two, 0, new int[] {1}); return two[0][0]; });
        show("set int[][] wrong", () -> { Array.set(two, 1, new long[1]); return null; });

        String[] src = {"a", "b", "c"};
        String[] bigger = Arrays.copyOf(src, 5);
        System.out.println(bigger.getClass().getName() + " " + Arrays.toString(bigger));
        String[] smaller = Arrays.copyOf(src, 2);
        System.out.println(smaller.getClass().getName() + " " + Arrays.toString(smaller));
        Object[] widened = Arrays.copyOf(src, 3, Object[].class);
        System.out.println(widened.getClass().getName() + " " + Arrays.toString(widened));
        Number[] nums = Arrays.copyOf(new Integer[] {1, 2}, 3, Number[].class);
        System.out.println(nums.getClass().getName() + " " + Arrays.toString(nums));
        String[] range = Arrays.copyOfRange(src, 1, 4);
        System.out.println(range.getClass().getName() + " " + Arrays.toString(range));
        Object[] plain = Arrays.copyOf(new Object[] {1}, 2);
        System.out.println(plain.getClass().getName() + " " + Arrays.toString(plain));
        try {
            Arrays.copyOf(src, 2, Integer[].class);
        } catch (ArrayStoreException e) {
            System.out.println("ArrayStoreException");
        }
        System.out.println("done");
    }
}
