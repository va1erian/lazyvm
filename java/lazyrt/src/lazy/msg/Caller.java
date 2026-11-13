package lazy.msg;

import lazy.internal.msg.Calls;

/**
 * Who sent the call a service is answering: the credential the kernel stamped on it (docs/msg.md
 * section 5). It is read with {@link #current} rather than passed to every method, so the methods
 * that do not authorize stay short.
 */
public final class Caller {
    private final long uid, gid, caps, label, session, sender;

    /** For the runtime: the stamped fields of one call. */
    public Caller(long uid, long gid, long caps, long label, long session, long sender) {
        this.uid = uid;
        this.gid = gid;
        this.caps = caps;
        this.label = label;
        this.session = session;
        this.sender = sender;
    }

    /**
     * The caller of the call being dispatched on this thread, or null. It is set while a server
     * method runs up to its first {@code await}, so a method that checks it reads it first.
     */
    public static Caller current() {
        return Calls.current();
    }

    public long uid() {
        return uid;
    }

    public long gid() {
        return gid;
    }

    /** The capability bits. */
    public long caps() {
        return caps;
    }

    /** The app label id; 0 for a platform task. */
    public long label() {
        return label;
    }

    /** The login session; 0 for none. */
    public long session() {
        return session;
    }

    /** The sending task's slot, for keying a per-client table only. */
    public long sender() {
        return sender;
    }

    @Override
    public String toString() {
        return "Caller[uid=" + uid + ", gid=" + gid + ", label=" + label + ", session=" + session + "]";
    }
}
