// VMARGS: -Dlazy.ui=headless
// NEEDS: ui
package lazy.ui;

import java.util.concurrent.CompletableFuture;

import lazy.async.Loop;
import lazy.internal.ui.Headless;

/**
 * While Ui.run runs, Loop.home() on the UI thread is Ui.executor(), so work resuming after a stage
 * that completes on another thread comes back to the UI thread (docs/siesta-async.md section 4).
 * Other threads have no home, and the UI thread has none once Ui.run has returned.
 */
public class Home {
    public static void main(String[] args) throws Exception {
        Headless.font(Home.class, "DroidSans.ttf");
        final Label label = Ui.label("start");
        Window w = Ui.window("Home", 320, 120).content(Ui.column().padding(8).add(label));
        Headless.run(w, () -> {
            System.out.println("UI thread: " + (Loop.home() == Ui.executor()));
            final CompletableFuture<Boolean> off = new CompletableFuture<>();
            Thread worker = new Thread(() -> off.complete(Loop.home() == null));
            worker.start();
            final CompletableFuture<String> later = new CompletableFuture<>();
            final java.util.concurrent.Executor home = Loop.home();
            later.thenAcceptAsync(s -> label.text(s), home);
            new Thread(() -> later.complete("resumed on the UI thread")).start();
            try {
                System.out.println("worker: " + off.get());
                later.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Headless.drain();
            System.out.println("label: " + label.text());
        });
        System.out.println("after Ui.run: " + (Loop.home() == null));
    }
}
