// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Dialogs, menus and shortcuts, driven by keys: a message accepted with Enter, a prompt answered
 * by typing, a question cancelled with Escape, a task dialog's commands, a context menu walked
 * with the arrow keys, and window shortcuts that run whatever has the focus.
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
        final Dialog message = w.message("Note", "Something happened.")
            .onClose(s -> System.out.println("message closed: " + s));
        final Dialog prompt = w.prompt("Link", "The address:", "https://")
            .acceptLabel("Link").onClose(s -> System.out.println("prompt closed: " + s));
        final Dialog question = w.confirm("Sure?", "Really?")
            .onClose(s -> System.out.println("question closed: " + s));
        final TaskDialog task = w.task("Save changes?", "This document has unsaved changes.")
            .icon(TaskDialog.WARNING).command("Save").command("Discard")
            .onChoice(i -> System.out.println("task choice: " + i));
        final Menu menu = w.menu()
            .item("Cut", "Scissors", () -> System.out.println("menu: cut"))
            .item("Copy", () -> System.out.println("menu: copy"))
            .separator()
            .check("Wrap", false, on -> System.out.println("menu: wrap " + on))
            .submenu("More", null, m -> m.item("Deep", () -> System.out.println("menu: deep")));
        menu.enabled(0, false);
        Headless.run(w, () -> {
            Headless.click(field);
            Headless.key('s', Window.CTRL);
            Headless.key('s', Window.CTRL | Window.SHIFT);
            message.show();
            System.out.println("message shown: " + frame());
            Headless.key(Window.ENTER, 0);
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
            menu.checked(3, true);
            System.out.println("closed: " + frame());
        });
    }
}
