package lazy.internal.msg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * crates/lazyvm-lazyos}, and serves names there, one request asked for at a time. Topics
 * (S10-06) and channel objects (S10-03) are not built here yet and fail with ENOSYS rather than
 * pretend.
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
        } else if (answer.length == 6 && answer[0] == 1) {
            int errno = (answer[1] & 0xff) | (answer[2] & 0xff) << 8 | (answer[3] & 0xff) << 16 | answer[4] << 24;
            call.fail(errno, answer[5] != 0);
        } else {
            call.fail(Errno.EIO, false);
        }
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
        throw new Wire.Failure(Errno.ENOSYS, "topics are not built on LazyOS yet");
    }

    @Override
    public void unsubscribe(Object token) {}

    @Override
    public void publish(String topic, byte[] payload, boolean retained) {
        throw new Wire.Failure(Errno.ENOSYS, "topics are not built on LazyOS yet");
    }
}
