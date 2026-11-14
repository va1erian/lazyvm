package lazy.internal.msg;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import lazy.async.Loop;
import lazy.internal.Reactor;
import lazy.msg.MessengerException;

/**
 * One generated client's connection to a served name (docs/msg.md section 2): it sends each
 * call through the broker with the IDL's deadline or the one {@link #within} set, resends as the
 * IDL's {@code retry=} allows, and completes the call's stage on the loop.
 */
public final class Client {
    /** Turns a reply's standard error into the exception the program catches. */
    public interface Errors {
        MessengerException of(String where, Fault fault);
    }

    /** The plain mapping, for an interface that declares no errors. */
    public static final Errors ERRNO = (where, f) -> new MessengerException(where, f.code, f.sentence, f.domain, f.hint, f.docs);

    /** No deadline: {@code deadline=never}, or {@code within(0)}. */
    public static final long NEVER = -1;
    private static final long FROM_IDL = -2;

    private final String service, iface;
    private final Errors errors;
    private final long deadline;

    private Client(String service, String iface, Errors errors, long deadline) {
        this.service = service;
        this.iface = iface;
        this.errors = errors;
        this.deadline = deadline;
    }

    /** A client of {@code service}; nothing is resolved until the first call. */
    public static Client connect(String service, String iface, Errors errors) {
        if (service == null || service.isEmpty()) throw new IllegalArgumentException("no service name");
        return new Client(service, iface, errors, FROM_IDL);
    }

    /** The same name with every call bounded by {@code millis}; 0 means no bound. */
    public Client within(long millis) {
        if (millis < 0) throw new IllegalArgumentException("negative deadline: " + millis);
        return new Client(service, iface, errors, millis == 0 ? NEVER : millis);
    }

    /**
     * Sends one call. {@code idlDeadline} is the method's bound in milliseconds or {@link
     * #NEVER}; {@code safe} is {@code retry=safe}; {@code args} writes the request and {@code
     * reply} reads a reply that carries no error.
     */
    public <T> CompletionStage<T> call(long headerId, int method, String name, long idlDeadline, boolean safe,
                                       boolean oneway, Consumer<WireWriter> args, Function<WireReader, T> reply) {
        String where = iface + "." + name;
        CompletableFuture<T> result = new CompletableFuture<>();
        WireWriter body = new WireWriter();
        try {
            args.accept(body);
        } catch (Wire.Failure f) {
            result.completeExceptionally(new MessengerException(where, f.errno, f.getMessage(), "", null, null));
            return result;
        }
        long millis = deadline == FROM_IDL ? idlDeadline : deadline;
        Attempt<T> attempt = new Attempt<>(this, where, headerId, method, body, safe, oneway, reply, result,
                Math.max(millis, 0));
        bound(result, millis, where);
        // However the stage ends (an answer, a timeout, a cancel), the request is withdrawn:
        // withdrawing an answered one does nothing, and a timed-out one must not stay in flight.
        result.whenComplete((v, t) -> attempt.withdraw());
        attempt.send();
        return result;
    }

    /** Fails {@code result} with ETIMEDOUT after {@code millis}; with no bound, keeps the VM up until it ends. */
    private static void bound(CompletableFuture<?> result, long millis, String where) {
        if (millis > 0) {
            CompletableFuture<Void> timer = Loop.delay(millis, TimeUnit.MILLISECONDS).toCompletableFuture();
            timer.thenRun(() -> result.completeExceptionally(
                    new MessengerException(where, Errno.ETIMEDOUT, null, "", null, null)));
            result.whenComplete((v, t) -> timer.cancel(false));
        } else {
            Runnable release = Reactor.hold();
            result.whenComplete((v, t) -> release.run());
        }
    }

    /** One call through the broker, sent again at most once when the policy allows. */
    private static final class Attempt<T> {
        private final Client client;
        private final String where;
        private final long headerId;
        private final int method;
        private final byte[] body;
        private final List<Object> objects;
        private final boolean safe, oneway;
        private final Function<WireReader, T> reply;
        private final CompletableFuture<T> result;
        private final long millis;
        private Broker.Outgoing current;
        private boolean resent;

        Attempt(Client client, String where, long headerId, int method, WireWriter body, boolean safe,
                boolean oneway, Function<WireReader, T> reply, CompletableFuture<T> result, long millis) {
            this.client = client;
            this.where = where;
            this.headerId = headerId;
            this.method = method;
            this.body = body.toBytes();
            this.objects = body.objects();
            this.safe = safe;
            this.oneway = oneway;
            this.reply = reply;
            this.result = result;
            this.millis = millis;
        }

        void send() {
            Broker.Outgoing out = new Broker.Outgoing(client.service, headerId, method, body, objects, oneway, millis) {
                @Override
                public void answer(byte[] bytes) {
                    Loop.executor().execute(() -> settle(bytes));
                }

                @Override
                public void fail(int errno, boolean delivered) {
                    Loop.executor().execute(() -> failed(errno, delivered));
                }
            };
            synchronized (this) {
                current = out;
            }
            Broker.get().call(out);
        }

        synchronized void withdraw() {
            if (current != null) Broker.get().cancel(current);
        }

        private void settle(byte[] bytes) {
            if (result.isDone()) return;
            if (oneway) {
                result.complete(null);
                return;
            }
            try {
                Fault fault = WireReader.fault(bytes);
                if (fault != null) {
                    result.completeExceptionally(client.errors.of(where, fault));
                } else {
                    result.complete(reply.apply(new WireReader(bytes)));
                }
            } catch (Wire.Failure f) {
                result.completeExceptionally(new MessengerException(where, f.errno, f.getMessage(), "", null, null));
            } catch (RuntimeException | StackOverflowError e) {
                result.completeExceptionally(new MessengerException(where, Errno.EBADMSG, String.valueOf(e), "", null, null));
            }
        }

        /** A request never queued is sent again; one a dead peer took, only when {@code retry=safe}. */
        private void failed(int errno, boolean delivered) {
            if (result.isDone()) return;
            if (!resent && errno == Errno.EPIPE && (safe || !delivered)) {
                resent = true;
                send();
                return;
            }
            if (!delivered) closeOwnChannels();
            result.completeExceptionally(new MessengerException(where, errno, null, "", null, null));
        }

        /**
         * A request that never reached a service handed no channel to anyone: close the ends this
         * call made for its listeners. An end forwarded from another call belongs to that one.
         */
        private void closeOwnChannels() {
            for (Object o : objects) Listeners.closeIfLocal(o);
        }
    }
}
