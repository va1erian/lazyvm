package lazy.msg;

import app.test.demo.v1.Change;
import app.test.demo.v1.DemoTopics;
import app.test.demo.v1.Kind;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import lazy.internal.Homes;
import lazy.internal.msg.Broker;

/**
 * Topics (docs/msg.md section 6). Handlers run on the subscriber's home; here main registers an
 * executor it drains itself, so what was queued before a handler ran is visible: a retained value
 * first, a filter with a wildcard, every event of a buffered topic but only the newest of a latest
 * one, and a payload that does not decode skipped.
 */
public class Topics {
    static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();

    public static void main(String[] args) throws Exception {
        Executor home = task -> {
            synchronized (QUEUE) {
                QUEUE.add(task);
            }
        };
        Homes.register(Thread.currentThread(), home);

        DemoTopics.appTestState().publish(Kind.DIR).toCompletableFuture().get();
        Subscription state = DemoTopics.appTestState().subscribe(e -> System.out.println("state " + e));
        Subscription changes = DemoTopics.appTestChangedFilter(null).subscribe(e -> System.out.println("change " + e.topic() + " " + e.value()));
        Subscription onlyB = DemoTopics.appTestChanged("b").subscribe(e -> System.out.println("b only " + e.value().value));
        for (String v : new String[] {"1", "2", "3"}) {
            DemoTopics.appTestChanged("a").publish(new Change().key("a").value(v));
            DemoTopics.appTestState().publish(Kind.FILE);
        }
        DemoTopics.appTestChanged("b").publish(new Change().key("b"));
        DemoTopics.appTestState().publish(Kind.DIR);
        Broker.get().publish("app/test/changed/x", new byte[] {9, 9}, false);
        drain();

        changes.close();
        DemoTopics.appTestChanged("a").publish(new Change().key("a").value("after close"));
        drain();
        state.close();
        onlyB.close();
        System.out.println("filter of key \"x/y\": " + attempt(() -> DemoTopics.appTestChanged("x/y")));
        System.out.println("filter of null: " + DemoTopics.appTestChangedFilter(null).filter());
        Homes.register(Thread.currentThread(), null);
    }

    static void drain() {
        for (;;) {
            Runnable next;
            synchronized (QUEUE) {
                next = QUEUE.poll();
            }
            if (next == null) return;
            next.run();
        }
    }

    static String attempt(Runnable r) {
        try {
            r.run();
            return "accepted";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
