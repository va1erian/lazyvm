package lazy.gfx;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * lazy.gfx.Image decodes PNGs to 0xAARRGGBB: RGBA with partial alpha, a 2-bit palette with a
 * tRNS chunk, 16-bit gray (reduced to 8 bits), and rejects broken files with IOException.
 * The files were made with Python's zlib, so their pixels are known exactly.
 */
public class ImageDecode {
    static final byte[] RGBA = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 3, 0, 0, 0, 2, 8, 6, 0, 0, 0, -99, 116, 102, 26, 0, 0, 0, 32, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -16, -97, -31, 63, 67, 3, -120, 98, 96, 100, 98, -2, -1, -21, -9, -97, -1, -100, -100, -100, -100, 0, 125, -23, 9, -106, -78, 10, -74, 78, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126};
    static final byte[] PALETTE = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 2, 3, 0, 0, 0, 15, -40, -27, -73, 0, 0, 0, 12, 80, 76, 84, 69, 0, 0, 0, -1, -1, -1, 10, 20, 30, -56, 100, 50, 26, 28, 125, 88, 0, 0, 0, 3, 116, 82, 78, 83, 0, -1, 77, 97, 63, 4, -127, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -112, 102, -104, 0, 0, 0, -27, 0, -84, -15, 88, -29, 52, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126};
    static final byte[] GRAY16 = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 16, 0, 0, 0, 0, 106, -18, 71, 22, 0, 0, 0, 11, 73, 68, 65, 84, 120, -100, 99, 16, 50, 1, 0, 0, 91, 0, 71, -106, -5, 27, 101, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126};

    /** IHDR 16384x4096 1-bit gray, empty IDAT, IEND: 57 bytes declaring 64M pixels. */
    static final byte[] HUGE_GRAY = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 64, 0, 0, 0, 16, 0, 1, 0, 0, 0, 0, -94, 102, -60, -98, 0, 0, 0, 0, 73, 68, 65, 84, 53, -81, 6, 30, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126};

    static void show(String name, Image img) {
        StringBuilder sb = new StringBuilder(name + " " + img.width + "x" + img.height + ":");
        for (int p : img.argb) sb.append(' ').append(String.format("%08X", p));
        System.out.println(sb);
    }

    static void bad(String name, byte[] data) {
        try {
            Image.decode(data);
            System.out.println(name + ": decoded?");
        } catch (IOException e) {
            System.out.println(name + ": IOException");
        }
    }

    public static void main(String[] args) throws IOException {
        show("rgba", Image.decode(RGBA));
        show("palette", Image.decode(PALETTE));
        show("gray16", Image.decode(new ByteArrayInputStream(GRAY16)));
        bad("empty", new byte[0]);
        bad("text", "not a png".getBytes());
        byte[] cut = new byte[RGBA.length - 20];
        System.arraycopy(RGBA, 0, cut, 0, cut.length);
        bad("truncated", cut);
        byte[] crc = RGBA.clone();
        crc[30] ^= 1;
        bad("bad crc", crc);
        try {
            Image.decode(HUGE_GRAY);
            System.out.println("huge gray: decoded?");
        } catch (IOException e) {
            System.out.println("huge gray: " + e.getMessage());
        }
        try {
            Image.decode((byte[]) null);
        } catch (NullPointerException e) {
            System.out.println("null: NullPointerException");
        }
    }
}
