package lazy.internal.msg;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import lazy.msg.MessengerException;

/**
 * The standard error of a reply (LazyOS docs/midl.md, "Errors"): the code, the peer's sentence
 * and the optional detail. A server turns what its method failed with into one; a client turns
 * one back into the exception the program catches.
 */
public final class Fault {
    public final int code;
    public final String sentence, domain, hint, docs;

    public Fault(int code, String sentence, String domain, String hint, String docs) {
        this.code = code;
        this.sentence = sentence;
        this.domain = domain == null ? "" : domain;
        this.hint = hint;
        this.docs = docs;
    }

    /** What a server answers for {@code t}: its code when it is a Messenger failure, else EIO. */
    public static Fault of(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof MessengerException) {
            MessengerException e = (MessengerException) t;
            return new Fault(e.code(), e.sentence(), e.domain(), e.hint(), e.docs());
        }
        if (t instanceof Wire.Failure) return new Fault(((Wire.Failure) t).errno, t.getMessage(), "", null, null);
        if (t instanceof CancellationException) return new Fault(Errno.ECANCELED, "cancelled", "", null, null);
        String text = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        return new Fault(Errno.EIO, text, "", null, null);
    }

    /** The reply body that carries this fault. */
    public byte[] body() {
        return new WireWriter().error(code, sentence, domain, hint, docs).toBytes();
    }
}
