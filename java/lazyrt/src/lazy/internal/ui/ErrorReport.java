package lazy.internal.ui;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The report of an exception that ends a program after a {@code lazy.ui} window was open on a
 * window system: a UI app often has no console, so the host shows it (a message box on Windows;
 * docs/ui.md, "When a callback throws"). Headless runs and programs that opened no window show
 * nothing beyond the usual stack trace.
 */
public final class ErrorReport {
    private static volatile boolean windowOpened;

    private ErrorReport() {}

    /** Called by {@code lazy.ui.Ui.run} before it opens a window on a window system. */
    public static void windowOpening() {
        windowOpened = true;
    }

    /** Called by the VM's uncaught-exception path, after the stack trace is printed. */
    public static void uncaught(Throwable t) {
        if (!windowOpened) return;
        String text;
        try {
            StringWriter out = new StringWriter();
            t.printStackTrace(new PrintWriter(out, true));
            text = out.toString();
        } catch (Throwable broken) {
            // The exception's own toString or trace failed: its class still says what happened.
            text = t.getClass().getName();
        }
        try {
            show0("The program stopped", text);
        } catch (Throwable ignored) {
            // No host to show it (the plain lazyvm launcher): the trace on stderr stands.
        }
    }

    private static native void show0(String title, String text);
}
