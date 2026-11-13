package lazy.msg;

/** A topic subscription (docs/msg.md section 6). Closing it ends delivery; closing twice does nothing. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
