// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * A callback that throws stops the loop for good: later clicks run nothing, the script's own
 * steps stop, and Ui.run rethrows the original exception. Nobody catches it, so the program
 * ends with its stack trace and a nonzero status, and headless no dialog is shown.
 */
public class CallbackThrows {
    public static void main(String[] args) throws IOException {
        Headless.font(CallbackThrows.class, "DroidSans.ttf");
        final int[] clicks = {0};
        final Button button = Ui.button("Boom").onClick(() -> {
            clicks[0]++;
            throw new IllegalArgumentException("boom from a callback");
        });
        Window w = Ui.window("Throws", 320, 160).content(Ui.column().padding(8).add(button));
        Headless.run(w, () -> {
            Headless.click(button);
            Headless.click(button);
            System.out.println("clicks that ran: " + clicks[0]);
        });
        System.out.println("not reached");
    }
}
