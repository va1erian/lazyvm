package lazy.msg;

import java.util.concurrent.CompletionStage;
import lazy.internal.msg.Topics;

/** One concrete declared topic: publish to it, or subscribe to it alone (docs/msg.md section 6). */
public final class Topic<T> extends TopicFilter<T> {
    private final boolean retained;

    /** For generated code: the concrete name, the declared QoS and retention, the payload codec. */
    public Topic(String name, Qos qos, boolean retained, Topics.Codec<T> codec) {
        super(name, qos, codec);
        this.retained = retained;
    }

    /** The concrete name. */
    public String name() {
        return filter();
    }

    /** Whether the IDL declares it retained. */
    public boolean retained() {
        return retained;
    }

    /** Publishes {@code value}; the stage completes when the broker took it. */
    public CompletionStage<Void> publish(T value) {
        return Topics.publish(filter(), retained, codec(), value);
    }
}
