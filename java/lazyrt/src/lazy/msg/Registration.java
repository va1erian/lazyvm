package lazy.msg;

/** A served name (docs/msg.md section 5). Closing it withdraws the name; closing twice does nothing. */
public interface Registration extends AutoCloseable {
    /** The registered name. */
    String name();

    @Override
    void close();
}
