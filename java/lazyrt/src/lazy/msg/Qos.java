package lazy.msg;

/**
 * How the broker queues a topic's events for a subscriber that has not taken them yet (LazyOS
 * docs/messenger.md): {@code LATEST} keeps the newest per topic, {@code BUFFERED} keeps them all
 * in order, {@code CONFLATE} keeps one per topic like {@code LATEST}, and {@code RELIABLE} keeps
 * them all and never drops one.
 */
public enum Qos {
    LATEST, BUFFERED, CONFLATE, RELIABLE;

    /** Whether a newer event of the same topic replaces one not yet delivered. */
    public boolean keepsNewestOnly() {
        return this == LATEST || this == CONFLATE;
    }
}
