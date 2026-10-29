package lazy.gfx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A decoded picture: {@code width * height} pixels of 0xAARRGGBB, row by row. */
public final class Image {
    public final int width;
    public final int height;
    public final int[] argb;

    private Image(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /**
     * Decodes a PNG file held in {@code data}.
     *
     * @throws IOException if the file is malformed, or larger than LazyVM decodes: over
     *     2<sup>24</sup> pixels, or over 1 MiB per undecoded row (for example wider than 131072
     *     pixels of 16-bit RGBA)
     */
    public static Image decode(byte[] data) throws IOException {
        if (data == null) throw new NullPointerException("data");
        int[] size = new int[2];
        int[] argb = decodePng0(data, size);
        return new Image(size[0], size[1], argb);
    }

    /** Reads {@code in} to its end, then decodes it as a PNG. Does not close {@code in}. */
    public static Image decode(InputStream in) throws IOException {
        if (in == null) throw new NullPointerException("in");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) all.write(buf, 0, n);
        return decode(all.toByteArray());
    }

    /** Throws IOException on a malformed or unsupported file; fills {@code size} with w, h. */
    private static native int[] decodePng0(byte[] data, int[] size) throws IOException;
}
