package lazy.internal;

/** Services the VM provides to the class library. */
public final class VM {
    private VM() {
    }

    /** A property of the set the VM started with, which Java code cannot change. */
    public static native String getProperty(String key);

    /** The process environment as name, value, name, value... */
    public static native String[] environment();

    /** Whether environment names compare ignoring case on this platform. */
    public static native boolean environmentIgnoresCase();

    /** The properties the VM started with, as name, value, name, value... */
    public static native String[] properties();

    public static native void exit(int status);

    public static native void gc();

    public static native long freeMemory();

    public static native long totalMemory();

    public static native long maxMemory();

    public static native int availableProcessors();

    /** 64 fresh bits per call, different in every run (util.md R3): seeds, not cryptography. */
    public static native long entropy();

    /** The bytes of a class path resource (no leading slash), or null. */
    public static native byte[] getResource(String name);

    /** Whether `c` came from the boot class path. */
    public static native boolean isBootClass(Class<?> c);

    // Constant pool entries of a class, for annotation parsing (docs/design/reflection.md).
    static native String poolUtf8(Class<?> c, int index);

    static native int poolInt(Class<?> c, int index);

    static native long poolLong(Class<?> c, int index);

    static native float poolFloat(Class<?> c, int index);

    static native double poolDouble(Class<?> c, int index);

    /** The generated class implementing an annotation type: a subclass of AnnotationBase. */
    static native Class<?> annotationClass(Class<?> annotationType);

    /** Reports what the running thread's uncaught exception handler threw, with no use of the heap. */
    public static native void handlerFailed(Throwable thrown);

    /**
     * Called by the VM when an exception escapes the main thread: it goes to
     * the thread's handler, as for any other thread. What comes out of this the
     * VM reports itself (`handlerFailed`), as it may be for lack of memory before
     * any handler has started.
     */
    static void uncaught(Throwable t) {
        Thread current = Thread.currentThread();
        current.getUncaughtExceptionHandler().uncaughtException(current, t);
        try {
            System.err.flush();
        } catch (Throwable u) {
            // Not the handler's failure: with the heap full, this is all that went wrong.
        }
    }
}
