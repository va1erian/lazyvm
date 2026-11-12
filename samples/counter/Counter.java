import lazy.ui.Label;
import lazy.ui.Ui;

import static lazy.ui.Ui.*;

/**
 * The S9 sample: a label and a button that counts clicks (docs/ui.md), and a worker thread that
 * shows the seconds since start through {@code Ui.executor()}, the one way other threads reach
 * the UI.
 */
public final class Counter {
    public static void main(String[] args) {
        Label clicks = label("0 clicks");
        Label clock = label("0 s");
        int[] n = {0};
        Thread worker = new Thread(() -> {
            for (int s = 1; ; s++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                String text = s + " s";
                Ui.executor().execute(() -> clock.text(text));
            }
        }, "clock");
        worker.setDaemon(true);
        worker.start();
        Ui.run(window("Counter", 320, 160).content(
            column().padding(16).gap(8).add(
                clicks,
                button("Click me").onClick(() -> clicks.text(++n[0] + " clicks")),
                clock)));
    }
}
