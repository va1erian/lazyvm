package lazy.internal.msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import lazy.msg.Caller;

/**
 * The in-process broker (docs/msg.md section 7): a registry of names, the calls in flight to
 * each, and topics with their retained values, all inside this VM. It keeps Messenger's rules
 * that a program can observe: an unknown name is ENOENT, a withdrawn one fails its calls with
 * EPIPE, a retained value reaches a new subscriber first. It never stands in for a LazyOS
 * service: a name nobody served here is simply unknown.
 */
final class Loopback implements Broker {
    static final Loopback INSTANCE = new Loopback();
    /** Every call here comes from this process, so it has one credential. */
    private static final Caller SELF = new Caller(0, 0, 0, 0, 0, 1);
    private static final long PUBLISHER = 1;

    private final Map<String, Served> names = new HashMap<>();
    /** Calls answered by nobody yet, with the name serving each. */
    private final Map<Outgoing, Served> inFlight = new HashMap<>();
    private final List<Sub> subs = new ArrayList<>();
    private final Map<String, byte[]> retained = new LinkedHashMap<>();
    /**
     * Held across a publish or a subscribe while their events are handed to sinks, so a new
     * subscriber's retained value is queued before any later publish reaches it. Sinks only queue.
     */
    private final Object delivery = new Object();

    private Loopback() {}

    private static final class Served {
        final String name;
        final Consumer<Incoming> inbox;

        Served(String name, Consumer<Incoming> inbox) {
            this.name = name;
            this.inbox = inbox;
        }
    }

    private static final class Sub {
        final String filter;
        final Consumer<RawEvent> sink;

        Sub(String filter, Consumer<RawEvent> sink) {
            this.filter = filter;
            this.sink = sink;
        }
    }

    @Override
    public void call(Outgoing call) {
        Served target;
        synchronized (this) {
            target = names.get(call.service);
            if (target != null && !call.oneway) inFlight.put(call, target);
        }
        if (target == null) {
            call.fail(Errno.ENOENT, false);
            return;
        }
        target.inbox.accept(new Incoming(call.headerId, call.method, call.body, call.objects, SELF, call.oneway) {
            @Override
            public void reply(byte[] body) {
                if (!call.oneway && finish(call)) call.answer(body);
            }
        });
        if (call.oneway) call.answer(new byte[0]);
    }

    @Override
    public void cancel(Outgoing call) {
        finish(call);
    }

    /** Takes {@code call} out of the flight; true if it was still there, so only one end settles it. */
    private synchronized boolean finish(Outgoing call) {
        return inFlight.remove(call) != null;
    }

    @Override
    public Object register(String name, long iface, String ifaceName, Consumer<Incoming> inbox) {
        synchronized (this) {
            if (names.containsKey(name)) throw new Wire.Failure(Errno.EEXIST, name + " is already served");
            Served served = new Served(name, inbox);
            names.put(name, served);
            return served;
        }
    }

    @Override
    public void unregister(Object token) {
        List<Outgoing> orphans;
        synchronized (this) {
            Served served = (Served) token;
            if (names.get(served.name) != served) return;
            names.remove(served.name);
            orphans = new ArrayList<>();
            for (Map.Entry<Outgoing, Served> e : inFlight.entrySet()) {
                if (e.getValue() == served) orphans.add(e.getKey());
            }
            for (Outgoing o : orphans) inFlight.remove(o);
        }
        for (Outgoing o : orphans) o.fail(Errno.EPIPE, true);
    }

    @Override
    public Object subscribe(String filter, Consumer<RawEvent> sink, Runnable ended) {
        Sub sub = new Sub(filter, sink);
        synchronized (delivery) {
            List<RawEvent> first = new ArrayList<>();
            synchronized (this) {
                for (Map.Entry<String, byte[]> e : retained.entrySet()) {
                    if (TopicNames.matches(filter, e.getKey())) first.add(new RawEvent(e.getKey(), e.getValue(), true, PUBLISHER));
                }
            }
            for (RawEvent e : first) sink.accept(e);
            synchronized (this) {
                subs.add(sub);
            }
        }
        return sub;
    }

    @Override
    public synchronized void unsubscribe(Object token) {
        subs.remove(token);
    }

    @Override
    public CompletionStage<Void> publish(String topic, byte[] payload, boolean keep) {
        synchronized (delivery) {
            List<Sub> targets = new ArrayList<>();
            synchronized (this) {
                if (keep) retained.put(topic, payload);
                for (Sub s : subs) {
                    if (TopicNames.matches(s.filter, topic)) targets.add(s);
                }
            }
            RawEvent event = new RawEvent(topic, payload, false, PUBLISHER);
            for (Sub s : targets) s.sink.accept(event);
        }
        return CompletableFuture.completedFuture(null);
    }
}
