import lazy.ui.Label;
import lazy.ui.Ui;

import static lazy.ui.Ui.*;

/** The S9 sample: a label and a button that counts clicks (docs/ui.md). */
public final class Counter {
    public static void main(String[] args) {
        Label clicks = label("0 clicks");
        int[] n = {0};
        Ui.run(window("Counter", 320, 160).content(
            column().padding(16).gap(8).add(
                clicks,
                button("Click me").onClick(() -> clicks.text(++n[0] + " clicks")))));
    }
}
