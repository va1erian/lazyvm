package lazy.internal.msg;

/**
 * The errno values Messenger reports, with LazyOS's numbers (its {@code libmessenger::errno}),
 * their names and a short text for messages.
 */
public final class Errno {
    public static final int EPERM = 1, ENOENT = 2, EIO = 5, EAGAIN = 11, ENOMEM = 12, EACCES = 13,
            EBUSY = 16, EEXIST = 17, EINVAL = 22, ENOSPC = 28, EPIPE = 32, ENOSYS = 38,
            EBADMSG = 74, EMSGSIZE = 90, ENOTSUP = 95, ETIMEDOUT = 110, ECANCELED = 125;

    private static final int[] CODES = {EPERM, ENOENT, EIO, EAGAIN, ENOMEM, EACCES, EBUSY, EEXIST,
        EINVAL, ENOSPC, EPIPE, ENOSYS, EBADMSG, EMSGSIZE, ENOTSUP, ETIMEDOUT, ECANCELED};
    private static final String[] NAMES = {"EPERM", "ENOENT", "EIO", "EAGAIN", "ENOMEM", "EACCES",
        "EBUSY", "EEXIST", "EINVAL", "ENOSPC", "EPIPE", "ENOSYS", "EBADMSG", "EMSGSIZE", "ENOTSUP",
        "ETIMEDOUT", "ECANCELED"};
    private static final String[] TEXTS = {"not permitted", "no such service or name",
        "input/output error", "try again", "out of memory", "access denied", "busy",
        "already exists", "invalid argument", "no space left", "peer went away",
        "no such method", "malformed message", "message too large", "not supported",
        "timed out", "cancelled"};

    private Errno() {}

    /** {@code EPIPE} for 32, or the number for a code this table does not know. */
    public static String name(int code) {
        int i = indexOf(code);
        return i < 0 ? Integer.toUnsignedString(code) : NAMES[i];
    }

    /** A short text for {@code code}, or "error N". */
    public static String text(int code) {
        int i = indexOf(code);
        return i < 0 ? "error " + Integer.toUnsignedString(code) : TEXTS[i];
    }

    private static int indexOf(int code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == code) return i;
        }
        return -1;
    }
}
