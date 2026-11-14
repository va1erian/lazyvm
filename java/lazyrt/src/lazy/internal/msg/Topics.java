package lazy.internal.msg;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import lazy.async.Loop;
import lazy.internal.Homes;
import lazy.internal.Reactor;
import lazy.msg.Event;
import lazy.msg.MessengerException;
import lazy.msg.Qos;
import lazy.msg.Subscription;

/**
 * Topic delivery (docs/msg.md section 6): the broker's events are queued per subscription as its
 * QoS says and handed to the handler on the subscriber's home, one per turn, the retained value
 * first. A payload that does not decode is skipped with a line on stderr. A subscription made on
 * a thread with a home of its own (the UI thread while {@code Ui.run} runs) ends with that home.
 */
public final class Topics {
    /** A topic's payload codec: the generated encode and decode of its declared type. */
    public interface Codec<T> {
        byte[] encode(T value);

        T decode(WireReader body);
    }

    private Topics() {}

    public static <T> Subscription subscribe(String filter, Qos qos, Codec<T> codec, Consumer<Event<T>> handler) {
        if (handler == null) throw new NullPointerException("handler");
        if (qos == null) throw new NullPointerException("qos");
        Sub<T> sub = new Sub<>(qos, codec, handler, Calls.home());
        try {
            sub.attach(Broker.get().subscribe(filter, sub::offer));
        } catch (Wire.Failure f) {
            sub.close();
            throw new UncheckedIOException(refusal(filter, f));
        } catch (RuntimeException | Error e) {
            sub.close();
            throw e;
        }
        Homes.adopt(sub.closer);
        return sub;
    }

    public static <T> CompletionStage<Void> publish(String topic, boolean retained, Codec<T> codec, T value) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            byte[] payload = codec.encode(Wire.need(value, "value"));
            Broker.get().publish(topic, payload, retained).whenComplete((v, failure) -> Loop.executor().execute(() -> {
                Throwable t = failure instanceof CompletionException ? failure.getCause() : failure;
                if (t == null) done.complete(null);
                else done.completeExceptionally(t instanceof Wire.Failure ? refusal(topic, (Wire.Failure) t) : t);
            }));
        } catch (Wire.Failure f) {
            done.completeExceptionally(refusal(topic, f));
        }
        return done;
    }

    private static MessengerException refusal(String where, Wire.Failure f) {
        return new MessengerException(where, f.errno, f.getMessage(), "", null, null);
    }

    /** For tests: how many events reached {@code s}, before its QoS dropped any. */
    public static int arrived(Subscription s) {
        Sub<?> sub = (Sub<?>) s;
        synchronized (sub) {
            return sub.arrived;
        }
    }

    /** For tests: whether {@code s} is closed. */
    public static boolean closed(Subscription s) {
        Sub<?> sub = (Sub<?>) s;
        synchronized (sub) {
            return sub.closed;
        }
    }

    private static final class Sub<T> implements Subscription {
        private final Qos qos;
        private final Codec<T> codec;
        private final Consumer<Event<T>> handler;
        private final Executor home;
        private final Runnable release = Reactor.hold();
        private final ArrayDeque<Broker.RawEvent> queue = new ArrayDeque<>();
        /** What a home this subscription was made on runs when it ends. */
        final Runnable closer = this::close;
        private boolean scheduled, closed;
        private int arrived;
        private Object token;

        Sub(Qos qos, Codec<T> codec, Consumer<Event<T>> handler, Executor home) {
            this.qos = qos;
            this.codec = codec;
            this.handler = handler;
            this.home = home;
        }

        void offer(Broker.RawEvent event) {
            synchronized (this) {
                if (closed) return;
                arrived++;
                if (qos.keepsNewestOnly()) queue.removeIf(e -> e.topic.equals(event.topic) && !e.retained);
                queue.add(event);
                if (scheduled) return;
                scheduled = true;
            }
            home.execute(this::deliverOne);
        }

        private void deliverOne() {
            Broker.RawEvent next;
            synchronized (this) {
                next = closed ? null : queue.poll();
                if (next == null) {
                    scheduled = false;
                    return;
                }
            }
            try {
                T value = codec.decode(new WireReader(next.payload));
                handler.accept(new Event<>(next.topic, value, next.retained, next.publisher));
            } catch (Wire.Failure f) {
                System.err.println("lazy.msg: " + next.topic + ": payload does not decode: " + f.getMessage());
            } finally {
                home.execute(this::deliverOne);
            }
        }

        /** Records the broker's token; a handler that closed the subscription before this ends it now. */
        void attach(Object t) {
            boolean late;
            synchronized (this) {
                token = t;
                late = closed;
            }
            if (late) Broker.get().unsubscribe(t);
        }

        @Override
        public void close() {
            Object t;
            synchronized (this) {
                if (closed) return;
                closed = true;
                queue.clear();
                t = token;
            }
            if (t != null) Broker.get().unsubscribe(t);
            Homes.release(closer);
            release.run();
        }
    }
}
