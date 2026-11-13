package lazy.msg;

import app.test.demo.v1.Change;
import app.test.demo.v1.DemoError;
import app.test.demo.v1.DemoException;
import app.test.demo.v1.DemoServer;
import app.test.demo.v1.DemoTopics;
import app.test.demo.v1.Kind;
import app.test.demo.v1.StatReply;
import app.test.demo.v1.SumArgs;
import app.test.watch.v1.WatchListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import lazy.async.Loop;

/**
 * The app.test.demo service the lazy.msg tests share: a map, with a key for each failure a
 * service can answer with. Run on its own it prints what it serves.
 */
public class Store implements DemoServer {
    private final Map<String, String> data = new HashMap<>();
    private final List<WatchListener> watchers = new ArrayList<>();

    @Override
    public CompletionStage<String> get(String key) {
        switch (key) {
            case "locked": return failed(MessengerException.of(MessengerException.EACCES, "store locked"));
            case "busy": return failed(new DemoException(DemoError.BUSY, "busy compacting"));
            case "boom": throw new IllegalStateException("boom");
            case "null": return CompletableFuture.completedFuture(null);
            default: break;
        }
        if (!data.containsKey(key)) return failed(new DemoException(DemoError.NOT_FOUND));
        return CompletableFuture.completedFuture(data.get(key));
    }

    @Override
    public CompletionStage<Void> put(String key, String value) {
        data.put(key, value);
        for (WatchListener w : watchers) w.changed(key, value);
        DemoTopics.appTestChanged(key).publish(new Change().key(key).value(value));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<StatReply> stat(String key) {
        String value = data.get(key);
        StatReply r = new StatReply()
                .size(value == null ? 0 : value.length())
                .kind(key.endsWith("/") ? Kind.DIR : Kind.FILE)
                .tags(new ArrayList<>(Arrays.asList(key.split("/"))))
                .note(value == null ? null : "has " + value);
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public CompletionStage<Long> wait_(long millis) {
        return Loop.delay(millis, TimeUnit.MILLISECONDS).thenApply(v -> millis);
    }

    @Override
    public CompletionStage<Void> watch(String prefix, WatchListener events) {
        watchers.add(events);
        Peer.of(events).closed().thenRun(() -> {
            watchers.remove(events);
            System.out.println("store: a watcher went away");
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> poke(String text) {
        System.out.println("store: poked " + text);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Long> sum(SumArgs args) {
        long e = args.e == null ? 0 : args.e;
        return CompletableFuture.completedFuture(args.a + args.b + args.c + args.d + e);
    }

    @Override
    public CompletionStage<Long> whoami() {
        Caller caller = Caller.current();
        return CompletableFuture.completedFuture(caller == null ? -1 : caller.uid());
    }

    /** Tells every watcher the store is going. */
    void stop() {
        for (WatchListener w : watchers) w.gone();
    }

    static <T> CompletionStage<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    public static void main(String[] args) {
        Registration r = app.test.demo.v1.Demo.serve(new Store());
        System.out.println("serving " + r.name());
        r.close();
    }
}
