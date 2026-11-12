// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Dialogs, menus and shortcuts, driven by keys: a message accepted with Enter, a prompt answered
 * by typing, a question cancelled with Escape, a task dialog's commands, a context menu walked
 * with the arrow keys, and window shortcuts that run whatever has the focus. While a dialog is
 * open, a second one does not show, and shortcuts and the close button wait. A menu asks for
 * its switches' states each time it shows.
 */
public class Dialogs {
    static final char DOWN = 0x28;

    static String frame() {
        int[] f = Headless.frame();
        return f[0] + "x" + f[1] + " " + Integer.toHexString(f[2]);
    }

    public static void main(String[] args) throws IOException {
        Headless.font(Dialogs.class, "DroidSans.ttf");
        final Button anchor = Ui.button("Menu");
        final Edit field = Ui.edit();
        final Window w = Ui.window("Dialogs", 480, 320).content(Ui.column().padding(8).gap(8).add(anchor, field));
        w.shortcut('s', Window.CTRL, () -> System.out.println("shortcut Ctrl+S"));
        w.shortcut('s', Window.CTRL | Window.SHIFT, () -> System.out.println("shortcut Ctrl+Shift+S"));
        w.onClose(() -> System.out.println("close asked"));
        final Dialog message = w.message("Note", "Something happened.")
            .onClose(s -> System.out.println("message closed: " + s));
        final Dialog prompt = w.prompt("Link", "The address:", "https://")
            .acceptLabel("Link").onClose(s -> System.out.println("prompt closed: " + s));
        final Dialog question = w.confirm("Sure?", "Really?")
            .onClose(s -> System.out.println("question closed: " + s));
        final TaskDialog task = w.task("Save changes?", "This document has unsaved changes.")
            .icon(TaskDialog.WARNING).command("Save").command("Discard")
            .onChoice(i -> System.out.println("task choice: " + i));
        final boolean[] wrap = {false};
        final Menu menu = w.menu()
            .enabledWhen(() -> false, m -> m.item("Cut", "Scissors", () -> System.out.println("menu: cut")))
            .item("Copy", () -> System.out.println("menu: copy"))
            .separator()
            .check("Wrap", () -> wrap[0], on -> {
                wrap[0] = on;
                System.out.println("menu: wrap " + on);
            })
            .submenu("More", null, m -> m.item("Deep", () -> System.out.println("menu: deep")));
        Headless.run(w, () -> {
            Headless.click(field);
            Headless.key('s', Window.CTRL);
            Headless.key('s', Window.CTRL | Window.SHIFT);
            message.show();
            System.out.println("message shown: " + frame());
            question.show();
            Headless.key('s', Window.CTRL);
            Headless.close();
            System.out.println("over the message: " + frame());
            Headless.key(Window.ENTER, 0);
            Headless.key('s', Window.CTRL);
            Headless.close();
            prompt.show();
            Headless.type("lazy.test");
            Headless.key(Window.ENTER, 0);
            question.show();
            Headless.key(Window.ESCAPE, 0);
            task.show();
            System.out.println("task shown: " + frame());
            Headless.key(Window.ESCAPE, 0);
            menu.show(anchor);
            System.out.println("menu shown: " + frame());
            Headless.key(DOWN, 0);
            Headless.key(Window.ENTER, 0);
            System.out.println("closed: " + frame());
            wrap[0] = false;
            menu.show(anchor);
            System.out.println("menu shown again: " + frame());
            Headless.key(Window.ESCAPE, 0);
        });
    }
}
