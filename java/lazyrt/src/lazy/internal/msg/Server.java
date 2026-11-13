package lazy.internal.msg;

import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import lazy.internal.Reactor;
import lazy.msg.MessengerException;
import lazy.msg.Registration;

/**
 * Serving a name (docs/msg.md section 5): each request runs the generated dispatch on the home
 * of the thread that called {@link #serve}, with {@code Caller.current()} set until the method
 * returns its stage, and the stage's value or failure becomes the reply.
 */
public final class Server {
    /** A request as the generated dispatch reads it. */
    public static final class Call {
        private final Broker.Incoming in;

        Call(Broker.Incoming in) {
            this.in = in;
        }

        public int method() {
            return in.method;
        }

        /** The arguments, objects included. */
        public WireReader args() {
            return new WireReader(in.body, in.objects);
        }

        /** Fails with EINVAL unless the header named {@code id} (the interface, or the method's scope). */
        public void expect(long id) {
            if (in.headerId != id) throw new Wire.Failure(Errno.EINVAL, "wrong interface for method " + in.method);
        }
    }

    /** The generated dispatch of one interface over one implementation. */
    public interface Dispatch {
        CompletionStage<WireWriter> dispatch(Call call);
    }

    private Server() {}

    /** Serves {@code name} as {@code iface}; a name already served fails with EEXIST. */
    public static Registration serve(String name, String iface, long ifaceId, Dispatch dispatch) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("no service name");
        Executor home = Calls.home();
        Object token;
        try {
            token = Broker.get().register(name, ifaceId, iface, in -> home.execute(() -> run(dispatch, in)));
        } catch (Wire.Failure f) {
            throw new UncheckedIOException(new MessengerException(name, f.errno, f.getMessage(), "", null, null));
        }
        return new Served(name, token, Reactor.hold());
    }

    /** Wraps a method's value as the reply {@code write} encodes; one that does not encode is EIO. */
    public static <T> CompletionStage<WireWriter> reply(CompletionStage<T> stage, BiConsumer<WireWriter, T> write) {
        if (stage == null) throw new Wire.Failure(Errno.EIO, "the method returned no stage");
        return stage.thenApply(value -> {
            WireWriter w = new WireWriter();
            try {
                write.accept(w, value);
            } catch (Wire.Failure f) {
                throw new Wire.Failure(Errno.EIO, "the reply does not encode: " + f.getMessage());
            }
            return w;
        });
    }

    private static void run(Dispatch dispatch, Broker.Incoming in) {
        CompletionStage<WireWriter> stage;
        Calls.enter(in.caller);
        try {
            stage = dispatch.dispatch(new Call(in));
        } catch (Wire.Failure f) {
            // A request that does not decode is the caller's fault: EINVAL, as LazyOS's dispatch says.
            int errno = f.errno == Errno.EBADMSG ? Errno.EINVAL : f.errno;
            CompletableFuture<WireWriter> failed = new CompletableFuture<>();
            failed.completeExceptionally(new Wire.Failure(errno, f.getMessage()));
            stage = failed;
        } catch (Throwable t) {
            CompletableFuture<WireWriter> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            stage = failed;
        } finally {
            Calls.leave();
        }
        stage.whenComplete((w, t) -> {
            if (in.oneway) {
                if (t != null && Fault.of(t).code != Errno.ECANCELED) Reactor.report(t);
                return;
            }
            in.reply(t == null ? w.toBytes() : Fault.of(t).body());
        });
    }

    private static final class Served implements Registration {
        private final String name;
        private final Object token;
        private final Runnable release;

        Served(String name, Object token, Runnable release) {
            this.name = name;
            this.token = token;
            this.release = release;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void close() {
            Broker.get().unregister(token);
            release.run();
        }
    }
}
