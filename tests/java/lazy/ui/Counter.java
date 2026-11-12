// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * The S9 sample headless: a label and a button that counts clicks. A thousand scripted clicks
 * make a thousand upcalls and leave the label at "1000 clicks"; the frames before and after are
 * pinned by their pixel checksums, drawn with the test tree's font.
 */
public class Counter {
    static String frame() {
        int[] f = Headless.frame();
        return f[0] + "x" + f[1] + " " + Integer.toHexString(f[2]);
    }

    public static void main(String[] args) throws IOException {
        Headless.font(Counter.class, "DroidSans.ttf");
        final Label clicks = Ui.label("0 clicks");
        final int[] n = {0};
        final Button button = Ui.button("Click me").onClick(() -> clicks.text(++n[0] + " clicks"));
        Window w = Ui.window("Counter", 320, 160).content(Ui.column().padding(16).gap(8).add(clicks, button));
        Headless.run(w, () -> {
            System.out.println("before: " + frame() + ", " + Headless.upcalls() + " upcalls");
            for (int i = 0; i < 1000; i++) Headless.click(button);
            System.out.println("label: " + clicks.text() + ", count " + n[0]);
            System.out.println("after: " + frame() + ", " + Headless.upcalls() + " upcalls");
        });
        System.out.println("run returned");
    }
}
