// VMARGS: -Dlazy.gfx=headless -Dlazy.gfx.dump=no-such-dir-lazy-gfx/frame.ppm
package lazy.gfx;

/**
 * A headless window whose PPM dump cannot be written: close() throws, yet the window is gone,
 * and its old Window object never reaches the next window opened (which may reuse its slot).
 */
public class HeadlessDumpFail {
    static void expect(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": no exception");
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getSimpleName());
        }
    }

    public static void main(String[] args) {
        final Window first = Window.open("first", 2, 2, 1);
        expect("close first", first::close);
        System.out.println("first open " + first.isOpen());
        final Window second = Window.open("second", 3, 1, 1);
        second.present(new int[] {0x010203, 0x040506, 0x070809}, 3, 1);
        System.out.println("first open " + first.isOpen() + ", second open " + second.isOpen());
        expect("present on first", () -> first.present(new int[4], 2, 2));
        expect("close first again", first::close);
        int[] px = second.capture();
        System.out.println("second " + second.width() + "x" + second.height() + " "
                + String.format("%06X %06X %06X", px[0], px[1], px[2]));
        expect("close second", second::close);
        System.out.println("second open " + second.isOpen());
    }
}
