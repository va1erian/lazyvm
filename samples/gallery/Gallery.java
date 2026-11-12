import lazy.ui.Ui;
import lazy.ui.Window;

/**
 * Window-host evidence for S9-03 and S9-05: once its window is up, it does what its argument
 * says.
 *
 * <ul>
 *   <li>{@code throw}: a callback throws and nobody catches it, so the program stops and, as a
 *       window was open, the host shows the error (a message box on Windows).
 *   <li>{@code catch}: the same exception, caught around {@code Ui.run}: no message box.
 *   <li>{@code dialog}: a task dialog over the window.
 *   <li>{@code window}: a second window beside the first.
 * </ul>
 */
public final class Gallery {
    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "dialog";
        Window w = Ui.window("Gallery: " + mode, 480, 300).content(Ui.column().padding(16).gap(8)
            .add(Ui.label("LazyVM lazy.ui on a window host").title(), Ui.label("Mode: " + mode)));
        Ui.executor().execute(() -> act(w, mode));
        if (!mode.equals("catch")) {
            Ui.run(w);
            return;
        }
        try {
            Ui.run(w);
        } catch (IllegalStateException e) {
            System.out.println("caught: " + e.getMessage());
        }
    }

    private static void act(Window w, String mode) {
        switch (mode) {
            case "throw":
            case "catch":
                Ui.after(300, () -> {
                    throw new IllegalStateException("thrown by a callback, " + mode);
                });
                break;
            case "window":
                Window second = Ui.window("Second window", 320, 200).content(Ui.column().padding(16)
                    .add(Ui.label("A second window, opened from a callback")));
                second.open();
                break;
            default:
                w.task("Save changes?", "This document has unsaved changes. Save them before you go on?")
                    .icon(lazy.ui.TaskDialog.WARNING).command("Save").command("Discard").show();
        }
    }
}
