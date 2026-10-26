package lazy.internal;

/** Services the VM provides to the class library. */
public final class VM {
    private VM() {
    }

    public static native String getProperty(String key);

    public static native void exit(int status);

    public static native void gc();

    public static native long freeMemory();

    public static native long totalMemory();

    public static native long maxMemory();

    public static native int availableProcessors();

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

    /** Called by the VM when an exception escapes the main thread. */
    static void uncaught(Throwable t) {
        System.err.print("Exception in thread \"" + Thread.currentThread().getName() + "\" ");
        t.printStackTrace(System.err);
        System.err.flush();
    }
}
