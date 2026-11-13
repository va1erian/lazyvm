package lazy.msg;

import java.util.concurrent.CompletionStage;
import lazy.internal.msg.Listeners;

/**
 * The other end of a callback channel (MIDL's {@code Channel<I>}, docs/msg.md section 5). A
 * service gets one with every listener it is passed; a client gets the ends of the listeners it
 * handed out.
 */
public interface Peer {
    /** Closes the channel; the other side sees it go. Closing twice does nothing. */
    void close();

    /** Completes when the channel closes, from either side. */
    CompletionStage<Void> closed();

    /**
     * The channel behind {@code listener}: on a service, a listener it was passed; on a client,
     * one it handed to a call (closing it closes every channel it was handed on).
     */
    static Peer of(Object listener) {
        return Listeners.peer(listener);
    }
}
