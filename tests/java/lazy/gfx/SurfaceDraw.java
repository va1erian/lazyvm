package lazy.gfx;

/**
 * lazy.gfx.Surface: fills by alpha, clearing, clipping, outlines, Bresenham lines, blits (flips,
 * alpha test, key and solid colours, blending, a surface onto itself), masks, wrapping an Image,
 * and the errors. Surfaces are tiny and start from known pixels, so each dump can be worked out
 * by hand from the rules in the Javadoc.
 */
public class SurfaceDraw {
    /** 3x2 RGBA: FFFF0000 8000FF00 000000FF FF010203 FFFAFBFC 09090909 (see ImageDecode). */
    static final byte[] RGBA = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 3, 0, 0, 0, 2, 8, 6, 0, 0, 0, -99, 116, 102, 26, 0, 0, 0, 32, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -16, -97, -31, 63, 67, 3, -120, 98, 96, 100, 98, -2, -1, -21, -9, -97, -1, -100, -100, -100, -100, 0, 125, -23, 9, -106, -78, 10, -74, 78, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126};

    static void show(String name, Surface s) {
        System.out.println(name + ":");
        for (int y = 0; y < s.height; y++) {
            StringBuilder row = new StringBuilder(" ");
            for (int x = 0; x < s.width; x++) row.append(' ').append(String.format("%08X", s.pixels[y * s.width + x]));
            System.out.println(row);
        }
    }

    static Surface black(int w, int h) {
        Surface s = new Surface(w, h);
        s.clear(0xFF000000);
        return s;
    }

    /** Alpha 0 (skipped), 128 (blended), 1 (the 25-bit "opaque" of Minicraft sheets) and 255. */
    static Surface sprite() {
        Surface s = new Surface(3, 2);
        int[] p = {0xFF110000, 0x00000000, 0x80FF0000, 0x01FFFFFF, 0xFF00FF00, 0x010000FF};
        System.arraycopy(p, 0, s.pixels, 0, p.length);
        return s;
    }

    interface Call {
        void run() throws Exception;
    }

    static void fails(String name, Call c) {
        try {
            c.run();
            System.out.println(name + ": no exception");
        } catch (Exception e) {
            System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Surface s = black(4, 3);
        s.fill(1, 0, 2, 2, 0xFF112233);
        s.fill(-5, -5, 6, 6, 0x80FFFFFF);
        s.fill(0, 2, 4, 1, 0x00FFFFFF);
        s.fill(3, 2, 9, 9, 0x40FF0000);
        s.fill(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 3, -1);
        s.fill(Integer.MIN_VALUE, 1, Integer.MAX_VALUE, 1, -1);
        show("fill", s);

        s = new Surface(4, 3);
        s.clip(1, 1, 2, 5);
        s.fill(0, 0, 4, 3, 0xFFAAAAAA);
        s.clip(2, 2, -1, 5);
        s.fill(0, 0, 4, 3, -1);
        s.clip(0, 0, 4, 3);
        s.fill(3, 0, 1, 1, 0xFF0000FF);
        s.clip(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 2);
        s.clear(0x12345678);
        show("clip", s);

        s = new Surface(5, 4);
        s.rect(0, 0, 5, 4, 0xFF0000FF);
        s.rect(1, 1, 3, 2, 0x80FFFFFF);
        s.rect(4, 3, 9, 9, 0xFF00FF00);
        s.rect(2, 2, 0, 5, -1);
        show("rect", s);

        s = new Surface(7, 5);
        s.line(0, 0, 6, 2, 0xFFFFFFFF);
        s.line(6, 4, 0, 3, 0xFF00FF00);
        s.line(3, -100, 3, 100, 0xFF0000FF);
        s.line(-2000000000, -2000000000, 2000000000, 2000000000, 0xFFFF0000);
        s.line(1, 4, 1, 4, 0x80FFFFFF);
        show("line", s);

        Surface src = sprite();
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, 0, 0, 0);
        show("blit blend", s);
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, 2, 1, Surface.ALPHA_TEST | Surface.FLIP_X);
        show("blit alpha test, flipped across, clipped", s);
        s = new Surface(3, 3);
        s.rect(0, 0, 3, 3, 0x80FFFFFF);
        show("rect on transparent pixels, corners painted once", s);
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, 0, 0, Surface.KEY | Surface.ALPHA_TEST, 0x00000000, 0xFF123456, 0);
        show("blit keying the transparent pixel", s);
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, 0, 0, Surface.ALPHA_TEST | Surface.KEY | Surface.SOLID | Surface.FLIP_Y,
               0x01FFFFFF, 0x00ABCDEF, 0x00FFFFFF);
        show("blit key and solid, flipped down", s);
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, 0, 0, Surface.SOLID, 0, 0, 0x00FFFFFF);
        show("blit solid, blended", s);
        s = black(4, 3);
        s.blit(src, 1, 0, 2, 2, 2, 1, Surface.FLIP_X | Surface.FLIP_Y);
        s.blit(src, 0, 0, 3, 2, Integer.MAX_VALUE, 0, 0);
        s.blit(src, 0, 0, 3, 2, Integer.MIN_VALUE, Integer.MIN_VALUE, 0);
        s.blit(src, 0, 0, 0, 2, 0, 0, 0);
        show("blit part, flipped both ways", s);
        s = new Surface(4, 1, new int[] {0xFF000001, 0xFF000002, 0xFF000003, 0xFF000004});
        s.blit(s, 0, 0, 3, 1, 1, 0, Surface.ALPHA_TEST);
        show("blit onto itself", s);
        s = black(4, 3);
        s.blit(src, 0, 0, 3, 2, -1, 0, Surface.FLIP_X);
        s.blit(src, 0, 0, 3, 2, 2, 2, Surface.FLIP_X | Surface.FLIP_Y);
        show("blit cut at the edges, flipped", s);
        s = new Surface(4, 3, new int[13]);
        s.clear(0xFF000000);
        s.clip(1, 0, 2, 3);
        s.blit(src, 0, 0, 3, 2, 1, 0, Surface.KEY, 0x01FFFFFF, 0x00ABCDEF, 0);
        s.line(0, 2, 3, 2, 0xFF0000FF);
        show("clipped blit with a key, blended; clipped line", s);
        System.out.println("past the surface: " + String.format("%08X", s.pixels[12]));

        s = black(3, 2);
        s.clear(0xFF404040);
        int[] mask = {-5, 999, 128, 0, 64, 255};
        s.blendMask(mask, 0x000000, 255);
        show("mask", s);
        s.clip(1, 0, 2, 1);
        s.blendMask(new int[] {255, 255, 255, 255, 255, 255}, 0xFFFFFF, 128);
        s.blendMask(mask, 0x123456, 0);
        show("mask, clipped, half strength", s);

        Image img = Image.decode(RGBA);
        Surface view = Surface.of(img);
        view.fill(0, 0, 1, 1, 0xFF00FF00);
        view.blit(view, 2, 1, 1, 1, 1, 0, 0);
        System.out.println("of image: " + view.width + "x" + view.height + " "
                + String.format("%08X %08X", img.argb[0], img.argb[1]));

        final Surface t = black(3, 2);
        final Surface sp = sprite();
        fails("negative size", () -> new Surface(-1, 2));
        fails("too large", () -> new Surface(65536, 65536));
        fails("short array", () -> new Surface(3, 2, new int[5]));
        fails("null array", () -> new Surface(2, 2, null));
        fails("null image", () -> Surface.of(null));
        fails("source outside", () -> t.blit(sp, 1, 0, 3, 1, 0, 0, 0));
        fails("source hidden but outside", () -> t.blit(sp, 0, 1, 1, 2, 50, 50, 0));
        fails("short form with KEY", () -> t.blit(sp, 0, 0, 1, 1, 0, 0, Surface.KEY));
        fails("unknown flag", () -> t.blit(sp, 0, 0, 1, 1, 0, 0, 64, 0, 0, 0));
        fails("null source", () -> t.blit(null, 0, 0, 1, 1, 0, 0, 0));
        fails("short mask", () -> t.blendMask(new int[5], 0, 10));
        fails("strength", () -> t.blendMask(new int[6], 0, 256));
        fails("null mask", () -> t.blendMask(null, 0, 10));
        show("after the errors", t);
    }
}
