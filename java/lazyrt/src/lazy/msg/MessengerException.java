package lazy.msg;

import java.io.IOException;
import lazy.internal.msg.Errno;

/**
 * A Messenger call, registration or publish that failed (docs/msg.md section 4). {@link #code}
 * is the errno-style code or, for an interface that declares errors, one of its codes, which
 * arrive as that interface's generated subclass. The message names the method, the peer's
 * sentence and the code: {@code os.lazy.confd.v1.Get: no such key (NotFound)}.
 */
public class MessengerException extends IOException {
    public static final int EPERM = Errno.EPERM, ENOENT = Errno.ENOENT, EIO = Errno.EIO,
            EACCES = Errno.EACCES, EEXIST = Errno.EEXIST, EINVAL = Errno.EINVAL,
            EPIPE = Errno.EPIPE, ENOSYS = Errno.ENOSYS, EBADMSG = Errno.EBADMSG,
            ETIMEDOUT = Errno.ETIMEDOUT, ECANCELED = Errno.ECANCELED;

    private final String where, sentence, domain, hint, docs;
    private final int code;
    private final boolean declared;

    /** A failure with an errno and the program's own sentence, for a service to answer with. */
    public static MessengerException of(int errno, String sentence) {
        return new MessengerException("", errno, sentence, "", null, null);
    }

    /**
     * A failure as the runtime reports it: {@code where} is the interface and method ({@code
     * os.lazy.echo.v1.Echo}) or empty, {@code sentence} the peer's text, and the rest the
     * optional detail of the standard error.
     */
    public MessengerException(String where, int code, String sentence, String domain, String hint, String docs) {
        this(where, code, sentence, domain, hint, docs, Errno.name(code), false);
    }

    /** For a generated subclass: {@code name} is the declared error's name. */
    protected MessengerException(String where, int code, String sentence, String hint, String name) {
        this(where, code, sentence, "", hint, null, name, true);
    }

    private MessengerException(String where, int code, String sentence, String domain, String hint,
                               String docs, String name, boolean declared) {
        super(message(where, sentence == null || sentence.isEmpty() ? Errno.text(code) : sentence, name));
        this.where = where == null ? "" : where;
        this.code = code;
        this.sentence = sentence == null || sentence.isEmpty() ? Errno.text(code) : sentence;
        this.domain = domain == null ? "" : domain;
        this.hint = hint;
        this.docs = docs;
        this.declared = declared;
    }

    private static String message(String where, String sentence, String name) {
        String head = where == null || where.isEmpty() ? sentence : where + ": " + sentence;
        return head + " (" + name + ")";
    }

    /** The code: an errno, or a declared error's code. */
    public int code() {
        return code;
    }

    /** The errno, or 0 when the code is a declared error or belongs to another domain. */
    public int errno() {
        return declared || !domain.isEmpty() ? 0 : code;
    }

    /** The peer's sentence, or a short text for the errno when it sent none. */
    public String sentence() {
        return sentence;
    }

    /** The code's namespace when it is not the service's own: empty for errnos. */
    public String domain() {
        return domain;
    }

    /** How to fix it, when the peer said; else null. */
    public String hint() {
        return hint;
    }

    /** A documentation id such as {@code err.messenger.denied}, when the peer gave one; else null. */
    public String docs() {
        return docs;
    }

    /** The interface and method that failed, or empty. */
    public String where() {
        return where;
    }
}
