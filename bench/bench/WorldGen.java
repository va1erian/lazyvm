package bench;

/** A noise and flood-fill kernel shaped like world generation: value noise, thresholds, region counts. */
public final class WorldGen {
    private static final int SIZE = 128;

    private WorldGen() {
    }

    public static void main(String[] args) {
        int worlds = 12 * Report.scale(args);
        long start = System.nanoTime();
        long sum = 0;
        for (int w = 0; w < worlds; w++) {
            byte[] tiles = generate(1000 + w);
            sum = sum * 31 + fillRegions(tiles);
        }
        Report.print("worldgen", sum, start);
    }

    private static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static int lerp(int a, int b, int t) {
        return a + (((b - a) * t) >> 8);
    }

    private static int noise(int x, int y, int cell, int seed) {
        int cx = x / cell;
        int cy = y / cell;
        int fx = (x % cell) * 256 / cell;
        int fy = (y % cell) * 256 / cell;
        int a = hash(cx, cy, seed) & 255;
        int b = hash(cx + 1, cy, seed) & 255;
        int c = hash(cx, cy + 1, seed) & 255;
        int d = hash(cx + 1, cy + 1, seed) & 255;
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static byte[] generate(int seed) {
        byte[] tiles = new byte[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int v = (noise(x, y, 32, seed) * 4 + noise(x, y, 16, seed + 1) * 2 + noise(x, y, 8, seed + 2)) / 7;
                tiles[y * SIZE + x] = (byte) (v < 90 ? 0 : v < 110 ? 1 : v < 150 ? 2 : 3);
            }
        }
        return tiles;
    }

    /** Labels every 4-connected region of equal tiles with an explicit stack; returns a digest. */
    private static long fillRegions(byte[] tiles) {
        int[] label = new int[tiles.length];
        int[] stack = new int[tiles.length];
        int regions = 0;
        long biggest = 0;
        for (int start = 0; start < tiles.length; start++) {
            if (label[start] != 0) {
                continue;
            }
            regions++;
            int kind = tiles[start];
            int top = 0;
            int count = 0;
            stack[top++] = start;
            label[start] = regions;
            while (top > 0) {
                int p = stack[--top];
                count++;
                int x = p % SIZE;
                int y = p / SIZE;
                if (x > 0 && label[p - 1] == 0 && tiles[p - 1] == kind) {
                    label[p - 1] = regions;
                    stack[top++] = p - 1;
                }
                if (x < SIZE - 1 && label[p + 1] == 0 && tiles[p + 1] == kind) {
                    label[p + 1] = regions;
                    stack[top++] = p + 1;
                }
                if (y > 0 && label[p - SIZE] == 0 && tiles[p - SIZE] == kind) {
                    label[p - SIZE] = regions;
                    stack[top++] = p - SIZE;
                }
                if (y < SIZE - 1 && label[p + SIZE] == 0 && tiles[p + SIZE] == kind) {
                    label[p + SIZE] = regions;
                    stack[top++] = p + SIZE;
                }
            }
            biggest = Math.max(biggest, count);
        }
        return regions * 1000003L + biggest;
    }
}
