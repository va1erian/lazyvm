package lazy.internal.msg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lazy.internal.Reactor;
import lazy.msg.Caller;

/**
 * The broker on LazyOS (docs/msg.md section 7): calls go to the reactor thread, which owns every
 * Messenger handle and begins, waits for and cancels each call through {@code
 * crates/lazyvm-lazyos}, serves names there, one request asked for at a time, and follows
 * topics through LazyOS's broker, one event asked for at a time. Channel objects (S10-03) are not
 * built here yet and fail with ENOSYS rather than pretend.
 */
final class NativeBroker implements Broker {
    static final NativeBroker INSTANCE = new NativeBroker();

    /** The reactor's stage of each call in flight, so {@link #cancel} can withdraw it. */
    private final Map<Outgoing, CompletionStage<byte[]>> inFlight = new WeakHashMap<>();
    /** Calls withdrawn before their stage was recorded, so {@link #call} withdraws them at once. */
    private final Map<Outgoing, Boolean> withdrawn = new WeakHashMap<>();

    private NativeBroker() {}

    @Override
    public void call(Outgoing call) {
        if (!call.objects.isEmpty()) {
            call.fail(Errno.ENOSYS, false);
            return;
        }
        CompletionStage<byte[]> stage = Reactor.call(call.service, call.headerId, call.method, call.body, call.oneway,
                call.deadlineMillis);
        boolean early;
        synchronized (this) {
            early = withdrawn.remove(call) != null;
            if (!early) inFlight.put(call, stage);
        }
        if (early) stage.toCompletableFuture().cancel(false);
        stage.whenComplete((answer, failure) -> {
            synchronized (this) {
                inFlight.remove(call);
            }
            if (failure != null) {
                call.fail(Errno.EIO, false);
            } else {
                settle(call, answer);
            }
        });
    }

    /** Reads the reactor's answer: {@code 0} and the body, or {@code 1}, the errno and delivered. */
    private static void settle(Outgoing call, byte[] answer) {
        if (answer.length >= 1 && answer[0] == 0) {
            byte[] body = new byte[answer.length - 1];
            System.arraycopy(answer, 1, body, 0, body.length);
            call.answer(body);
        } else {
            call.fail(errno(answer), answer.length == 6 && answer[0] == 1 && answer[5] != 0);
        }
    }

    /** The errno of a failed answer; EIO for one that is not an answer at all. */
    private static int errno(byte[] answer) {
        if (answer.length != 6 || answer[0] != 1) return Errno.EIO;
        return (answer[1] & 0xff) | (answer[2] & 0xff) << 8 | (answer[3] & 0xff) << 16 | answer[4] << 24;
    }

    @Override
    public void cancel(Outgoing call) {
        CompletionStage<byte[]> stage;
        synchronized (this) {
            stage = inFlight.remove(call);
            // Not recorded yet, or already over: either way call() must not keep it going.
            if (stage == null) withdrawn.put(call, Boolean.TRUE);
        }
        if (stage != null) stage.toCompletableFuture().cancel(false);
    }

    @Override
    public Object register(String name, long iface, String ifaceName, Consumer<Incoming> inbox) {
        long handle;
        try {
            handle = Reactor.serve(name, iface, ifaceName);
        } catch (IOException e) {
            throw new Wire.Failure(Errno.EIO, e.getMessage());
        }
        if (handle < 0) throw new Wire.Failure((int) -handle, refusal(name, (int) -handle));
        Served served = new Served(handle, inbox);
        receive(served);
        return served;
    }

    /** In the loopback broker's words, so a program reads the same on both. */
    private static String refusal(String name, int errno) {
        return errno == Errno.EEXIST ? name + " is already served" : name + ": " + Errno.text(errno);
    }

    /**
     * One request at a time is asked for; the next once this one is handed to the server's home.
     * A failed receive ends it (the name was withdrawn, or its endpoint is unusable; transient
     * errors never reach here), but a request that cannot be handed on is reported and the name
     * keeps being served.
     */
    private static void receive(Served served) {
        Reactor.receive(served.handle).whenComplete((answer, failure) -> {
            if (served.closed || failure != null || answer.length < 1 || answer[0] != 0) return;
            try {
                served.inbox.accept(incoming(answer));
            } catch (RuntimeException e) {
                Reactor.report(e);
            }
            receive(served);
        });
    }

    /** Reads {@code platform::messenger::Incoming} after the answer's leading 0. */
    private static Incoming incoming(byte[] answer) {
        ByteBuffer in = ByteBuffer.wrap(answer, 1, answer.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        long txn = in.getLong(), iface = in.getLong();
        Caller caller = new Caller(in.getLong(), in.getLong(), in.getLong(), in.getLong(), in.getLong(), in.getLong());
        int method = in.getInt();
        byte[] body = new byte[in.remaining()];
        in.get(body);
        AtomicBoolean replied = new AtomicBoolean();
        return new Incoming(iface, method, body, Collections.emptyList(), caller, txn == 0) {
            @Override
            public void reply(byte[] reply) {
                if (oneway || !replied.compareAndSet(false, true)) return;
                try {
                    Reactor.reply(txn, reply);
                } catch (IOException e) {
                    // The caller is told by the kernel when its transaction ends unanswered.
                }
            }
        };
    }

    @Override
    public void unregister(Object token) {
        Served served = (Served) token;
        if (served.closed) return;
        served.closed = true;
        try {
            Reactor.unserve(served.handle);
        } catch (IOException e) {
            // The name goes with the process at the latest.
        }
    }

    private static final class Served {
        final long handle;
        final Consumer<Incoming> inbox;
        volatile boolean closed;

        Served(long handle, Consumer<Incoming> inbox) {
            this.handle = handle;
            this.inbox = inbox;
        }
    }

    @Override
    public Object subscribe(String filter, Consumer<RawEvent> sink) {
        long handle;
        try {
            handle = Reactor.subscribe(filter);
        } catch (IOException e) {
            throw new Wire.Failure(Errno.EIO, e.getMessage());
        }
        if (handle < 0) throw new Wire.Failure((int) -handle, Errno.text((int) -handle));
        Followed followed = new Followed(handle, sink);
        next(followed);
        return followed;
    }

    /**
     * One event at a time is asked for; the next once this one is handed to the sink. A failure
     * other than the subscription's own end is reported, and ends it.
     */
    private static void next(Followed followed) {
        Reactor.next(followed.handle).whenComplete((answer, failure) -> {
            if (followed.closed) return;
            if (failure != null || answer.length < 1 || answer[0] != 0) {
                int errno = failure != null ? Errno.EIO : errno(answer);
                System.err.println("lazy.msg: a subscription ended: " + Errno.text(errno));
                return;
            }
            try {
                followed.sink.accept(event(answer));
            } catch (RuntimeException e) {
                Reactor.report(e);
            }
            next(followed);
        });
    }

    /** Reads {@code platform::messenger::TopicEvent} after the answer's leading 0. */
    private static RawEvent event(byte[] answer) {
        ByteBuffer in = ByteBuffer.wrap(answer, 1, answer.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        long publisher = in.getLong();
        boolean retained = in.get() != 0;
        byte[] topic = new byte[in.getInt()];
        in.get(topic);
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        return new RawEvent(new String(topic, StandardCharsets.UTF_8), payload, retained, publisher);
    }

    @Override
    public void unsubscribe(Object token) {
        Followed followed = (Followed) token;
        if (followed.closed) return;
        followed.closed = true;
        try {
            Reactor.unsubscribe(followed.handle);
        } catch (IOException e) {
            // The subscription goes with the process at the latest.
        }
    }

    private static final class Followed {
        final long handle;
        final Consumer<RawEvent> sink;
        volatile boolean closed;

        Followed(long handle, Consumer<RawEvent> sink) {
            this.handle = handle;
            this.sink = sink;
        }
    }

    @Override
    public CompletionStage<Void> publish(String topic, byte[] payload, boolean retained) {
        return Reactor.publish(topic, payload, retained).thenApply(answer -> {
            if (answer.length >= 1 && answer[0] == 0) return null;
            int errno = errno(answer);
            throw new Wire.Failure(errno, Errno.text(errno));
        });
    }
}
