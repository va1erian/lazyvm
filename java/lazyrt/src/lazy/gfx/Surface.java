package lazy.gfx;

/**
 * Pixels to draw on: {@code width * height} values of 0xAARRGGBB, row by row, in
 * {@link #pixels} (docs/gfx.md). The program may also read and write the array itself, and show
 * it with {@link Window#present}. The drawing is native, so a sprite costs one call rather than
 * a loop in the interpreter.
 *
 * <p>Every call draws only inside the clip rectangle, which is the whole surface unless
 * {@link #clip} narrows it. A rectangle with no area draws nothing, and no coordinate is too
 * large: what falls outside is cut away. A colour's alpha decides how it lands: 0 leaves the
 * pixel alone, 255 replaces it, and anything between is drawn over it, each channel rounded to
 * nearest. Three writes take a colour as it is instead: {@link #clear}, {@link #blit}'s copies
 * under {@link #ALPHA_TEST}, and the colour it puts in place of a {@link #KEY} pixel. A surface
 * is not thread-safe.
 */
public final class Surface {
    /** {@link #blit} flag: mirror the source left to right. */
    public static final int FLIP_X = 1;
    /** {@link #blit} flag: mirror the source top to bottom. */
    public static final int FLIP_Y = 2;
    /** {@link #blit} flag: copy each source pixel whose alpha is not 0 as it is, never blending. */
    public static final int ALPHA_TEST = 4;
    /** {@link #blit} flag: source pixels equal to {@code key} become {@code keyColor}, as it is. */
    public static final int KEY = 8;
    /** {@link #blit} flag: every other source pixel that shows takes the colour {@code solid}. */
    public static final int SOLID = 16;

    public final int width;
    public final int height;
    /** The pixels, at least {@code width * height} of them; the drawing uses only that many. */
    public final int[] pixels;

    private int clipX0, clipY0, clipX1, clipY1;

    /** A new surface of {@code width * height} transparent black pixels. */
    public Surface(int width, int height) {
        this(width, height, new int[area(width, height)]);
    }

    /** A surface over {@code pixels}, drawn on in place; the array may be longer than needed. */
    public Surface(int width, int height, int[] pixels) {
        if (pixels == null) throw new NullPointerException("pixels");
        if (area(width, height) > pixels.length)
            throw new IllegalArgumentException(width + "x" + height + " over " + pixels.length + " pixels");
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        clipX1 = width;
        clipY1 = height;
    }

    /** A surface over {@code image}'s pixels, drawn on in place. */
    public static Surface of(Image image) {
        if (image == null) throw new NullPointerException("image");
        return new Surface(image.width, image.height, image.argb);
    }

    private static int area(int width, int height) {
        long area = (long) width * height;
        if (width < 0 || height < 0 || area > Integer.MAX_VALUE)
            throw new IllegalArgumentException("size " + width + "x" + height);
        return (int) area;
    }

    /**
     * Limits drawing to the part of this rectangle that lies on the surface (none, if it misses
     * or has no area). {@code clip(0, 0, width, height)} lifts the limit.
     */
    public void clip(int x, int y, int w, int h) {
        clipX0 = clamp(x, 0, width);
        clipY0 = clamp(y, 0, height);
        clipX1 = clamp((long) x + Math.max(w, 0), clipX0, width);
        clipY1 = clamp((long) y + Math.max(h, 0), clipY0, height);
    }

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /** Sets every pixel in the clip to {@code argb}, alpha included. */
    public void clear(int argb) {
        fill0(pixels, width, height, clipX0, clipY0, clipX1, clipY1,
              clipX0, clipY0, clipX1 - clipX0, clipY1 - clipY0, argb, false);
    }

    /** Paints {@code argb} on the {@code w} by {@code h} pixels at ({@code x}, {@code y}). */
    public void fill(int x, int y, int w, int h, int argb) {
        fill0(pixels, width, height, clipX0, clipY0, clipX1, clipY1, x, y, w, h, argb, true);
    }

    /**
     * Paints {@code argb} on the one-pixel border of the {@code w} by {@code h} rectangle at
     * ({@code x}, {@code y}), each pixel once. (AWT's {@code drawRect(x, y, w, h)} covers
     * {@code w + 1} by {@code h + 1} pixels.)
     */
    public void rect(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        edge(x, y, w, 1, argb);
        if (h > 1) edge(x, (long) y + h - 1, w, 1, argb);
        if (h > 2) {
            edge(x, (long) y + 1, 1, h - 2, argb);
            if (w > 1) edge((long) x + w - 1, (long) y + 1, 1, h - 2, argb);
        }
    }

    /** One fill of a border; an edge past the largest coordinate is off every surface. */
    private void edge(long x, long y, int w, int h, int argb) {
        if (x > Integer.MAX_VALUE || y > Integer.MAX_VALUE) return;
        fill0(pixels, width, height, clipX0, clipY0, clipX1, clipY1, (int) x, (int) y, w, h, argb, true);
    }

    /**
     * Paints {@code argb} on a one-pixel line from ({@code x0}, {@code y0}) to ({@code x1},
     * {@code y1}), both ends included, with the pixels of Bresenham's algorithm (those of AWT's
     * {@code drawLine}).
     */
    public void line(int x0, int y0, int x1, int y1, int argb) {
        line0(pixels, width, height, clipX0, clipY0, clipX1, clipY1, x0, y0, x1, y1, argb);
    }

    /**
     * Draws the {@code w} by {@code h} pixels at ({@code sx}, {@code sy}) of {@code src} with their
     * top left corner at ({@code dx}, {@code dy}). {@code flags} may hold {@link #FLIP_X},
     * {@link #FLIP_Y} and {@link #ALPHA_TEST}; {@link #KEY} and {@link #SOLID} need the colours of
     * the longer form. {@code src} may be this surface.
     *
     * @throws IndexOutOfBoundsException if the source rectangle does not lie inside {@code src},
     *     even where the clip hides it
     * @throws IllegalArgumentException if {@code flags} holds {@link #KEY}, {@link #SOLID} or any
     *     other bit
     */
    public void blit(Surface src, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        if ((flags & (KEY | SOLID)) != 0)
            throw new IllegalArgumentException("KEY and SOLID need their colours");
        blit(src, sx, sy, w, h, dx, dy, flags, 0, 0, 0);
    }

    /**
     * Like the shorter {@link #blit}, with colours. For each source pixel: with {@link #KEY}, one
     * equal to {@code key} writes {@code keyColor} as it is; otherwise one of alpha 0 is skipped;
     * the rest are drawn in their own colour, or in {@code solid}'s with {@link #SOLID}, at the
     * source pixel's alpha. With {@link #ALPHA_TEST} that colour is written as it is instead:
     * the source pixel itself, or {@code solid} with its own alpha byte.
     *
     * @throws IllegalArgumentException if {@code flags} has other bits
     */
    public void blit(Surface src, int sx, int sy, int w, int h, int dx, int dy, int flags,
                     int key, int keyColor, int solid) {
        if (src == null) throw new NullPointerException("src");
        blit0(pixels, width, height, clipX0, clipY0, clipX1, clipY1, src.pixels, src.width,
              src.height, sx, sy, w, h, dx, dy, flags, key, keyColor, solid);
    }

    /**
     * Draws {@code rgb} over each pixel in the clip at the opacity {@code mask[i] * strength / 255}
     * (rounded), where {@code i} is the pixel's index in {@link #pixels} and mask values are taken
     * as 0 below 0 and 255 above 255. For lighting: a mask of shadows at a strength set by the time
     * of day.
     *
     * @throws IllegalArgumentException if {@code mask} has fewer than {@code width * height}
     *     values or {@code strength} is not in 0 to 255
     */
    public void blendMask(int[] mask, int rgb, int strength) {
        if (mask == null) throw new NullPointerException("mask");
        blendMask0(pixels, width, height, clipX0, clipY0, clipX1, clipY1, mask, rgb, strength);
    }

    private static native void fill0(int[] px, int w, int h, int cx0, int cy0, int cx1, int cy1,
                                     int x, int y, int rw, int rh, int argb, boolean blend);

    private static native void line0(int[] px, int w, int h, int cx0, int cy0, int cx1, int cy1,
                                     int x0, int y0, int x1, int y1, int argb);

    private static native void blit0(int[] px, int w, int h, int cx0, int cy0, int cx1, int cy1,
                                     int[] src, int sw, int sh, int sx, int sy, int bw, int bh,
                                     int dx, int dy, int flags, int key, int keyColor, int solid);

    private static native void blendMask0(int[] px, int w, int h, int cx0, int cy0, int cx1,
                                          int cy1, int[] mask, int rgb, int strength);
}
