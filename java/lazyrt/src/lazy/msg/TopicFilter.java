package lazy.msg;

import java.util.function.Consumer;
import lazy.internal.msg.Topics;

/**
 * Topics to subscribe to: a declared topic with some placeholders left as wildcards
 * (docs/msg.md section 6). The generated {@code ...Filter} accessors make these.
 */
public class TopicFilter<T> {
    private final String filter;
    private final Qos qos;
    private final Topics.Codec<T> codec;

    /** For generated code: the filter, its declared QoS and the payload codec. */
    public TopicFilter(String filter, Qos qos, Topics.Codec<T> codec) {
        this.filter = filter;
        this.qos = qos;
        this.codec = codec;
    }

    /** The filter, wildcards included. */
    public String filter() {
        return filter;
    }

    /** The QoS the IDL declares. */
    public Qos qos() {
        return qos;
    }

    /** Delivers each event to {@code handler} on this thread's home, the retained values first. */
    public Subscription subscribe(Consumer<Event<T>> handler) {
        return subscribe(qos, handler);
    }

    /** The same with another QoS. */
    public Subscription subscribe(Qos qos, Consumer<Event<T>> handler) {
        return Topics.subscribe(filter, qos, codec, handler);
    }

    Topics.Codec<T> codec() {
        return codec;
    }

    @Override
    public String toString() {
        return filter;
    }
}
