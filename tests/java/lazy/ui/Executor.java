// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Other threads hand work to the UI thread through Ui.executor(): a worker submits a hundred
 * label updates, which run on the UI thread in the order submitted. Every other lazy.ui call off
 * the UI thread throws IllegalStateException.
 */
public class Executor {
    public static void main(String[] args) throws Exception {
        Headless.font(Executor.class, "DroidSans.ttf");
        final Label label = Ui.label("start");
        final StringBuilder order = new StringBuilder();
        final Thread ui = Thread.currentThread();
        final String[] offThread = {null};
        Window w = Ui.window("Executor", 320, 120).content(Ui.column().padding(8).add(label));
        Headless.run(w, () -> {
            Thread worker = new Thread(() -> {
                for (int i = 1; i <= 100; i++) {
                    final int n = i;
                    Ui.executor().execute(() -> {
                        if (Thread.currentThread() != ui) order.append("WRONG THREAD ");
                        order.append(n).append(n % 10 == 0 ? "\n" : " ");
                        label.text("update " + n);
                    });
                }
                try {
                    label.text("from the worker");
                } catch (IllegalStateException e) {
                    offThread[0] = e.getMessage();
                }
            });
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("before draining: " + label.text());
            Headless.drain();
            System.out.print(order);
            System.out.println("label: " + label.text());
            System.out.println("off the UI thread: " + offThread[0]);
        });
    }
}
