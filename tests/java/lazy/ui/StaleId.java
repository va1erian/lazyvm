// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Ids carry a generation. A callback edits a rich-text document inside a panel, which queues its
 * change message until the callback returns, then replaces the panel's content; the new editor
 * takes the old one's registry slot with the next generation. The queued message is dropped, so
 * the new editor's handler never sees it, and the old editor, gone, refuses changes.
 */
public class StaleId {
    static RichText editor(final String name, final StringBuilder log) {
        return Ui.richText().onChange(words -> log.append(name).append(" changed, ").append(words).append(" words\n"));
    }

    public static void main(String[] args) throws IOException {
        Headless.font(StaleId.class, "DroidSans.ttf");
        final StringBuilder log = new StringBuilder();
        final RichText first = editor("first", log);
        final RichText second = editor("second", log);
        final Panel panel = Ui.panel(Ui.column().add(first.fill(1)));
        final int[] ids = new int[2];
        final Button edit = Ui.button("Edit").onClick(() -> first.exec(RichText.PAGE_BREAK));
        final Button swap = Ui.button("Edit and swap").onClick(() -> {
            first.exec(RichText.PAGE_BREAK);
            ids[0] = Headless.id(first);
            panel.set(Ui.column().add(second.fill(1)));
            ids[1] = Headless.id(second);
        });
        Window w = Ui.window("Stale", 320, 240).content(
            Ui.column().padding(8).gap(8).add(edit, swap, panel.fill(1)));
        Headless.run(w, () -> {
            Headless.click(edit);
            System.out.print(log);
            log.setLength(0);
            int before = Headless.upcalls();
            Headless.click(swap);
            System.out.println("same slot: " + ((ids[0] & 0xffff) == (ids[1] & 0xffff)));
            System.out.println("same id: " + (ids[0] == ids[1]));
            System.out.println("upcalls for the swap: " + (Headless.upcalls() - before));
            System.out.println("handlers run after the swap: [" + log + "]");
            try {
                first.exec(RichText.UNDO);
            } catch (IllegalStateException e) {
                System.out.println("old editor: " + e.getMessage());
            }
        });
    }
}
