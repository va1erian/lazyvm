package lazy.msg;

/** One delivered topic event: the concrete topic, its decoded value, and how it came. */
public final class Event<T> {
    private final String topic;
    private final T value;
    private final boolean retained;
    private final long publisher;

    /** For the runtime. */
    public Event(String topic, T value, boolean retained, long publisher) {
        this.topic = topic;
        this.value = value;
        this.retained = retained;
        this.publisher = publisher;
    }

    /** The topic it was published on, wildcards resolved. */
    public String topic() {
        return topic;
    }

    public T value() {
        return value;
    }

    /** True for the retained value a new subscription is given first. */
    public boolean retained() {
        return retained;
    }

    /** The publishing task, as the broker stamped it. */
    public long publisher() {
        return publisher;
    }

    @Override
    public String toString() {
        return topic + (retained ? " (retained): " : ": ") + value;
    }
}
