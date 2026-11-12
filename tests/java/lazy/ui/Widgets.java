// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Every widget kind in one window, then driven: clicks on a button, a toggle, a check box and a
 * toolbar item, typing into both kinds of text field, picks in a list and on tabs, and changes
 * made from Java. The frame checksum pins what it all draws, in the test tree's font.
 */
public class Widgets {
    static final StringBuilder log = new StringBuilder();

    static void say(String s) {
        log.append(s).append('\n');
    }

    static String frame() {
        int[] f = Headless.frame();
        return f[0] + "x" + f[1] + " " + Integer.toHexString(f[2]);
    }

    public static void main(String[] args) throws IOException {
        Headless.font(Widgets.class, "DroidSans.ttf");
        final Label title = Ui.label("Widgets").title();
        final Button button = Ui.button("Press").icon("Check").onClick(() -> say("button"));
        final ToggleButton bold = Ui.toggle("").icon("Bold").onToggle(on -> say("bold " + on));
        final CheckBox check = Ui.checkBox("Check me").onToggle(on -> say("check " + on));
        final Edit edit = Ui.edit().placeholder("type here").onChange(s -> say("edit " + s));
        final Edit area = Ui.textArea().text("first line");
        final ComboBox combo = Ui.comboBox("One", "Two", "Three").icon(1, "Star").onSelect(i -> say("combo " + i));
        final ListView list = Ui.list().column("Name", 0).column("Size", 60)
            .rows("a.txt", "1 KB", "b.txt", "2 KB", "c.txt", "3 KB")
            .onSelect(i -> say("list select " + i)).onActivate(i -> say("list open " + i));
        final Slider slider = Ui.slider(0, 100).value(25);
        final ProgressBar progress = Ui.progress(10).value(3);
        final StatusBar status = Ui.statusBar("Ready", "0 items");
        final Toolbar tools = Ui.toolbar()
            .item("FilePlus", "New", "New", () -> say("tool new"))
            .separator()
            .item("Save", "Save", () -> say("tool save"));
        final Tabs tabs = Ui.tabs()
            .page("One", Ui.column().add(Ui.label("Page one")))
            .page("Two", Ui.column().add(Ui.label("Page two")))
            .onChange(i -> say("tab " + i));
        final Canvas canvas = Ui.canvas(80, 40, new Drawing().clear(0xffffff)
            .fillRect(4, 4, 30, 30, 0xff3366cc).line(40, 4, 76, 36, 0xff000000, 2)
            .fillEllipse(44, 8, 24, 24, 0xffcc3333));
        Layout left = Ui.column().gap(6).add(title, Ui.row().gap(6).add(button, bold.width(32), check),
            edit, area.height(48), combo, slider, progress);
        Layout right = Ui.column().gap(6).add(list.height(110), tabs.height(80), canvas,
            Ui.scroll(Ui.column().add(Ui.label("In a scroll"))).height(30),
            Ui.group("Group", Ui.column().add(Ui.label("In a group"))));
        Window w = Ui.window("Widgets", 640, 420).content(Ui.column().add(tools.height(36),
            Ui.row().padding(8).gap(12).add(left.fill(1), Ui.verticalSeparator(), right.fill(1)).fill(1),
            status));
        Headless.run(w, () -> {
            System.out.println("built: " + frame());
            Headless.click(button);
            Headless.click(bold);
            Headless.click(check);
            Headless.click(edit);
            Headless.type("hi");
            Headless.click(area);
            Headless.type("!");
            Headless.click(list);
            Headless.click(tools, 20, 18);
            System.out.print(log);
            log.setLength(0);
            System.out.println("bold " + bold.checked() + ", check " + check.checked());
            System.out.println("edit '" + edit.text() + "', list row " + list.selected());
            combo.select(2);
            list.rows("x", "1", "y", "2");
            list.select(1);
            tabs.select(1);
            slider.value(80);
            progress.value(10);
            status.text(1, "2 items");
            title.text("Changed");
            button.enabled(false);
            Headless.click(button);
            canvas.drawing(new Drawing().clear(0xff000000).text("Hi", 0, 0, 80, 40, 0xffffffff, 20));
            System.out.print(log);
            System.out.println("combo " + combo.selected() + ", list " + list.selected() + " of " + list.count()
                + ", tab " + tabs.selected() + ", slider " + slider.value());
            System.out.println("changed: " + frame());
            System.out.println("upcalls: " + Headless.upcalls());
        });
    }
}
