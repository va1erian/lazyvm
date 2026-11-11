// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.io.IOException;

import lazy.internal.ui.Headless;

/**
 * Input the program does not listen to runs no bytecode: a hundred hovers and a hundred wheel
 * notches over a mounted window make no upcall, nor does typing into a field without onChange.
 * Ten characters typed into a field with onChange make ten.
 */
public class IdleCost {
    public static void main(String[] args) throws IOException {
        Headless.font(IdleCost.class, "DroidSans.ttf");
        final Button button = Ui.button("Hover me").onClick(() -> System.out.println("clicked"));
        final Label label = Ui.label("A label");
        final Edit quiet = Ui.edit();
        final StringBuilder seen = new StringBuilder();
        final Edit loud = Ui.edit().onChange(s -> seen.append('[').append(s).append(']'));
        Window w = Ui.window("Idle", 320, 240).content(
            Ui.column().padding(8).gap(8).add(label, button, quiet, loud));
        Headless.run(w, () -> {
            for (int i = 0; i < 100; i++) {
                Headless.hover(i % 2 == 0 ? button : label);
                Headless.scroll(label, i % 2 == 0 ? 1 : -1);
            }
            System.out.println("hover and scroll: " + Headless.upcalls() + " upcalls");
            Headless.click(quiet);
            Headless.type("quiet text");
            System.out.println("typing without onChange: " + Headless.upcalls() + " upcalls, text " + quiet.text());
            Headless.click(loud);
            Headless.type("0123456789");
            System.out.println("typing with onChange: " + Headless.upcalls() + " upcalls");
            System.out.println(seen);
        });
    }
}
