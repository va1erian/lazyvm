// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Timers run on the headless driver's clock, in time order: a repeating one every 100 ms, a
 * one-shot at 250 ms that cancels the repeating one, and Ui.later work that runs after the
 * callback that queued it, or once the window is up when queued before Ui.run. A second window
 * opens from a callback, takes clicks, and closes without ending the loop.
 */
public class Timers {
    public static void main(String[] args) throws IOException {
        Headless.font(Timers.class, "DroidSans.ttf");
        final Label label = Ui.label("ticks: 0");
        final int[] ticks = {0};
        final Button second = Ui.button("In the second window").onClick(() -> System.out.println("second window clicked"));
        final Window other = Ui.window("Second", 240, 120).content(Ui.column().padding(8).add(second));
        final Button open = Ui.button("Open").onClick(() -> other.open());
        Window w = Ui.window("Timers", 320, 160).content(Ui.column().padding(8).gap(8).add(label, open));
        Ui.later(() -> System.out.println("queued before run: " + Headless.frame()[0] + " wide"));
        Headless.run(w, () -> {
            System.out.println("script starts");
            final Timer every = Ui.every(100, () -> {
                label.text("ticks: " + ++ticks[0]);
                Ui.later(() -> System.out.println("later after tick " + ticks[0]));
                System.out.println("tick " + ticks[0]);
            });
            Ui.after(250, () -> {
                System.out.println("one-shot at 250, cancelling");
                every.cancel();
            });
            Headless.advance(1000);
            System.out.println(label.text() + ", active " + every.active());
            Headless.click(open);
            Headless.click(second);
            other.close();
            System.out.println("second window closed, main still running: " + label.text());
            Headless.advance(500);
            System.out.println("upcalls: " + Headless.upcalls());
        });
        System.out.println("run returned");
    }
}
