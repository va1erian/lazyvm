// VMARGS: -Dlazy.gfx=headless
package lazy.gfx;

/**
 * A headless lazy.gfx window: frames are scaled by nearest neighbour to fit, centred on black,
 * and capture() returns exactly what was presented. Also the argument checks, polling with no
 * input, closing, and key names.
 */
public class HeadlessWindow {
    /** Rows of the captured window, each as runs of "count*RRGGBB". */
    static void show(Window w) {
        int[] px = w.capture();
        System.out.println(w.width() + "x" + w.height() + " (" + px.length + " pixels)");
        for (int y = 0; y < w.height(); y++) {
            StringBuilder sb = new StringBuilder(" ");
            int x = 0;
            while (x < w.width()) {
                int p = px[y * w.width() + x], n = 0;
                while (x < w.width() && px[y * w.width() + x] == p) { x++; n++; }
                sb.append(' ').append(n).append('*').append(String.format("%06X", p));
            }
            System.out.println(sb);
        }
    }

    static void expect(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": no exception");
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getSimpleName());
        }
    }

    public static void main(String[] args) {
        final Window w = Window.open("test", 4, 2, 3);
        System.out.println("open " + w.isOpen());
        show(w);
        // The alpha byte is ignored on screen.
        w.present(new int[] {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF,
                             0x123456, 0xFF654321, 0x000000, 0x808080}, 4, 2);
        show(w);
        // A square frame in a 2:1 window gets bars left and right.
        w.present(new int[] {0x111111, 0x222222, 0x333333, 0x444444}, 2, 2);
        show(w);
        // A frame using only part of a larger array.
        w.present(new int[] {0xABCDEF, 1, 2, 3, 4, 5, 6, 7, 8}, 2, 1);
        show(w);

        Event e = new Event();
        System.out.println("poll " + w.poll(e));
        expect("short array", () -> w.present(new int[3], 2, 2));
        expect("zero width", () -> w.present(new int[3], 0, 2));
        expect("null pixels", () -> w.present(null, 1, 1));
        expect("bad scale", () -> Window.open("x", 4, 4, 0));
        expect("over 2^25 pixels", () -> Window.open("x", 8193, 4096, 1));
        expect("side over int", () -> Window.open("x", 1 << 20, 1, 1 << 12));
        w.setTitle("renamed");
        w.close();
        w.close();
        System.out.println("open after close " + w.isOpen() + ", poll " + w.poll(e));
        expect("present after close", () -> w.present(new int[1], 1, 1));
        expect("capture after close", () -> w.capture());

        int[] keys = {'A', 'Z', '0', Key.SPACE, Key.COMMA, Key.ESCAPE, Key.UP, Key.ALT,
                      Key.F1, Key.F1 + 11, Key.F1 + 12, Key.UNKNOWN, -5};
        StringBuilder sb = new StringBuilder("keys:");
        for (int k : keys) sb.append(' ').append(Key.name(k));
        System.out.println(sb);
    }
}
