package bench;

/** A sprite blit loop: copy 16x16 tiles with a transparency test and a palette lookup. */
public final class Blit {
    private static final int W = 256;
    private static final int H = 144;
    private static final int TILE = 16;
    private static final int SHEET_W = 128;

    private Blit() {
    }

    public static void main(String[] args) {
        int frames = 200 * Report.scale(args);
        int[] sheet = new int[SHEET_W * SHEET_W];
        int seed = 12345;
        for (int i = 0; i < sheet.length; i++) {
            seed = seed * 1103515245 + 12345;
            sheet[i] = (seed >>> 16) & 0x3FF;
        }
        int[] palette = new int[256];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = 0xFF000000 | (i * 0x010203);
        }
        int[] screen = new int[W * H];
        long start = System.nanoTime();
        long sum = 0;
        for (int f = 0; f < frames; f++) {
            for (int ty = 0; ty < H / TILE; ty++) {
                for (int tx = 0; tx < W / TILE; tx++) {
                    int sx = ((tx + f) & 7) * TILE;
                    int sy = ((ty * 3 + f) & 7) * TILE;
                    blit(screen, tx * TILE, ty * TILE, sheet, sx, sy, palette, f & 1);
                }
            }
            sum += screen[(f * 37) % screen.length];
        }
        for (int i = 0; i < screen.length; i += 7) {
            sum = sum * 31 + screen[i];
        }
        Report.print("blit", sum, start);
    }

    private static void blit(int[] dst, int x, int y, int[] sheet, int sx, int sy, int[] palette, int flip) {
        for (int j = 0; j < TILE; j++) {
            int d = (y + j) * W + x;
            int s = (sy + j) * SHEET_W + sx;
            for (int i = 0; i < TILE; i++) {
                int px = sheet[s + (flip == 0 ? i : TILE - 1 - i)];
                if ((px & 0x300) != 0x300) {
                    dst[d + i] = palette[px & 0xFF];
                }
            }
        }
    }
}
