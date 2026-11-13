package lazy.internal.msg;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import lazy.msg.Peer;

/**
 * Callback channels (MIDL's {@code Channel<I>}, docs/msg.md section 5). A client hands a call a
 * listener object; {@link #local} makes the end that carries it, whose events run the
 * listener's methods on the client's home. The service decodes that end into a generated remote
 * object whose methods send the events back ({@link #endpoint}).
 */
public final class Listeners {
    /** Runs one received event on a listener: the generated {@code deliver} of its interface. */
    public interface Deliver {
        void deliver(Object listener, int method, WireReader args);
    }

    /** Where a remote listener's events go, and the channel's life. */
    public interface Endpoint extends Peer {
        /** Sends one event; {@code headerId} is the listener interface's id. Dropped once closed. */
        void send(long headerId, int method, WireWriter body);
    }

    /** The ends each client-side listener was handed on, for {@code Peer.of(listener)}. */
    private static final Map<Object, List<Local>> HANDED = new IdentityHashMap<>();

    private Listeners() {}

    /** The channel end to write for {@code listener}, whose events {@code deliver} runs. */
    public static Object local(Object listener, Deliver deliver) {
        Wire.need(listener, "listener");
        if (listener instanceof Remote) return new Forwarded(((Remote) listener).endpoint());
        Local end = new Local(listener, deliver, Calls.home());
        synchronized (HANDED) {
            HANDED.computeIfAbsent(listener, k -> new ArrayList<>()).add(end);
        }
        end.closed().thenRun(() -> forget(listener, end));
        return end;
    }

    /** The endpoint behind a decoded channel object. */
    public static Endpoint endpoint(Object raw) {
        if (raw instanceof Endpoint) return (Endpoint) raw;
        throw Wire.malformed("not a channel end: " + raw);
    }

    /** Closes {@code end} if {@link #local} made it here; a forwarded remote end is left alone. */
    static void closeIfLocal(Object end) {
        if (end instanceof Local) ((Local) end).close();
    }

    /** {@code Peer.of}: a remote's own channel, or every channel a local listener was handed on. */
    public static Peer peer(Object listener) {
        if (listener instanceof Peer) return (Peer) listener;
        List<Local> ends;
        synchronized (HANDED) {
            List<Local> list = HANDED.get(listener);
            ends = list == null ? new ArrayList<>() : new ArrayList<>(list);
        }
        if (ends.isEmpty()) throw new IllegalArgumentException("not a listener handed to a call: " + listener);
        return new Group(ends);
    }

    private static void forget(Object listener, Local end) {
        synchronized (HANDED) {
            List<Local> list = HANDED.get(listener);
            if (list != null && list.remove(end) && list.isEmpty()) HANDED.remove(listener);
        }
    }

    /** A generated remote listener: what it sends on. */
    public interface Remote {
        Endpoint endpoint();
    }

    /** A client's end of an in-process channel: it runs each event on the listener's home. */
    private static final class Local implements Endpoint {
        private final Object listener;
        private final Deliver deliver;
        private final Executor home;
        private final CompletableFuture<Void> closed = new CompletableFuture<>();

        Local(Object listener, Deliver deliver, Executor home) {
            this.listener = listener;
            this.deliver = deliver;
            this.home = home;
        }

        @Override
        public void send(long headerId, int method, WireWriter body) {
            if (closed.isDone()) return;
            byte[] bytes = body.toBytes();
            List<Object> objects = body.objects();
            home.execute(() -> {
                if (!closed.isDone()) deliver.deliver(listener, method, new WireReader(bytes, objects));
            });
        }

        @Override
        public void close() {
            closed.complete(null);
        }

        @Override
        public CompletionStage<Void> closed() {
            return closed;
        }
    }

    /**
     * A received channel end handed on in another call. It is the same channel, but this call
     * did not make it, so a failed call does not close it ({@link #closeIfLocal}).
     */
    private static final class Forwarded implements Endpoint {
        private final Endpoint end;

        Forwarded(Endpoint end) {
            this.end = end;
        }

        @Override
        public void send(long headerId, int method, WireWriter body) {
            end.send(headerId, method, body);
        }

        @Override
        public void close() {
            end.close();
        }

        @Override
        public CompletionStage<Void> closed() {
            return end.closed();
        }
    }

    /** Several channels of one listener, closed together. */
    private static final class Group implements Peer {
        private final List<Local> ends;
        private final CompletableFuture<Void> all;

        Group(List<Local> ends) {
            this.ends = ends;
            CompletableFuture<?>[] each = new CompletableFuture<?>[ends.size()];
            for (int i = 0; i < each.length; i++) each[i] = ends.get(i).closed.toCompletableFuture();
            this.all = CompletableFuture.allOf(each);
        }

        @Override
        public void close() {
            for (Local end : ends) end.close();
        }

        @Override
        public CompletionStage<Void> closed() {
            return all;
        }
    }
}
