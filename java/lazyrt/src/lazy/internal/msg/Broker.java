package lazy.internal.msg;

import java.util.List;
import java.util.function.Consumer;
import lazy.msg.Caller;

/**
 * What the Messenger runtime needs from a transport: calls, served names and topics. The
 * loopback broker implements it in-process (Windows, and anywhere LazyOS's is absent); LazyOS's
 * kernel and {@code messengerd} will implement it through {@code crates/lazyvm-lazyos} (S10-02).
 * Every method may be called from any thread and answers through the callbacks it is given.
 */
public interface Broker {
    /** Sends {@code call}; it ends in exactly one {@link Outgoing#answer} or {@link Outgoing#fail}. */
    void call(Outgoing call);

    /** Withdraws {@code call}: its answer, if one comes, is dropped. */
    void cancel(Outgoing call);

    /** Serves {@code name}; fails with EEXIST when it is taken. Returns the token to unregister with. */
    Object register(String name, Consumer<Incoming> inbox);

    /** Withdraws a name; its calls in flight fail with EPIPE. */
    void unregister(Object token);

    /** Subscribes to {@code filter}; the retained values that match arrive first. Returns the token. */
    Object subscribe(String filter, Consumer<RawEvent> sink);

    void unsubscribe(Object token);

    /** Publishes {@code payload} on the concrete topic {@code topic}. */
    void publish(String topic, byte[] payload, boolean retained);

    /** The broker this process uses. */
    static Broker get() {
        return Loopback.INSTANCE;
    }

    /** A request on its way: where it goes, its body and objects, and how it ends. */
    abstract class Outgoing {
        public final String service;
        public final long headerId;
        public final int method;
        public final byte[] body;
        public final List<Object> objects;
        public final boolean oneway;

        protected Outgoing(String service, long headerId, int method, byte[] body, List<Object> objects, boolean oneway) {
            this.service = service;
            this.headerId = headerId;
            this.method = method;
            this.body = body;
            this.objects = objects;
            this.oneway = oneway;
        }

        /** The reply body (empty for a {@code oneway} the broker queued). */
        public abstract void answer(byte[] reply);

        /** No reply: {@code delivered} is false when the request was never queued, so a resend is safe. */
        public abstract void fail(int errno, boolean delivered);
    }

    /** A request a served name received. */
    abstract class Incoming {
        public final long headerId;
        public final int method;
        public final byte[] body;
        public final List<Object> objects;
        public final Caller caller;
        public final boolean oneway;

        protected Incoming(long headerId, int method, byte[] body, List<Object> objects, Caller caller, boolean oneway) {
            this.headerId = headerId;
            this.method = method;
            this.body = body;
            this.objects = objects;
            this.caller = caller;
            this.oneway = oneway;
        }

        /** Answers it; ignored for a {@code oneway}, and after the first. */
        public abstract void reply(byte[] body);
    }

    /** One topic event as the broker delivers it, payload still encoded. */
    final class RawEvent {
        public final String topic;
        public final byte[] payload;
        public final boolean retained;
        public final long publisher;

        public RawEvent(String topic, byte[] payload, boolean retained, long publisher) {
            this.topic = topic;
            this.payload = payload;
            this.retained = retained;
            this.publisher = publisher;
        }
    }
}
