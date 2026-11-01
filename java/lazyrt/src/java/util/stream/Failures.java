package java.util.stream;

/** Collecting several failures into one: the first stays primary and the rest are suppressed into it. */
final class Failures {
    private Failures() {
    }

    /** `first` with `later` attached; `later` itself when nothing failed before. Never suppresses into itself. */
    static Throwable add(Throwable first, Throwable later) {
        if (first == null) {
            return later;
        }
        if (first != later) {
            first.addSuppressed(later);
        }
        return first;
    }

    /** Rethrows a Throwable without declaring it, for code whose body could only throw unchecked ones. */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> E raise(Throwable t) throws E {
        throw (E) t;
    }

    /** Runs `body`, then closes `s` whatever happened. A failure of the body stays primary. */
    static void thenClose(BaseStream<?, ?> s, Runnable body) {
        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable t) {
            failure = t;
        }
        try {
            s.close();
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else {
                // The same object as the body's fails here with "Self-suppression not permitted", as on the oracle.
                failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw Failures.<RuntimeException>raise(failure);
        }
    }
}
