// VMARGS: -Dlazy.ui=headless
// NEEDS: msg
package lazy.msg;

import app.test.demo.v1.Change;
import app.test.demo.v1.DemoTopics;
import java.util.concurrent.CompletableFuture;
import lazy.internal.ui.Headless;
import lazy.ui.Label;
import lazy.ui.Ui;
import lazy.ui.Window;

/**
 * A topic handler made on the UI thread runs there, and Ui.run's return closes its subscription:
 * the program ends although it never closed it, and an event published afterwards reaches no one.
 */
public class UiTopics {
    public static void main(String[] args) throws Exception {
        Headless.font(lazy.ui.Home.class, "DroidSans.ttf");
        final Thread ui = Thread.currentThread();
        final Label label = Ui.label("nothing yet");
        final CompletableFuture<String> where = new CompletableFuture<>();
        final Subscription[] sub = new Subscription[1];
        Window w = Ui.window("UiTopics", 320, 120).content(Ui.column().padding(8).add(label));
        Headless.run(w, () -> {
            sub[0] = DemoTopics.appTestChanged("ui").subscribe(e -> {
                label.text(e.value().value);
                where.complete(Thread.currentThread() == ui ? "the UI thread" : "another thread");
            });
            try {
                DemoTopics.appTestChanged("ui").publish(new Change().key("ui").value("hello")).toCompletableFuture().get();
                Topics.arrive(sub[0], 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // The handler is queued for this thread, which is busy until now.
            Headless.drain();
            System.out.println("handler ran on " + where.getNow("no thread"));
            System.out.println("label: " + label.text());
        });
        System.out.println("closed by Ui.run: " + lazy.internal.msg.Topics.closed(sub[0]));
        DemoTopics.appTestChanged("ui").publish(new Change().key("ui").value("too late")).toCompletableFuture().get();
        System.out.println("events after Ui.run: " + (lazy.internal.msg.Topics.arrived(sub[0]) - 1));
    }
}
