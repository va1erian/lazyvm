package java.lang;

import lazy.internal.VM;

public class Runtime {
    private static final Runtime CURRENT = new Runtime();

    private Runtime() {
    }

    public static Runtime getRuntime() {
        return CURRENT;
    }

    public void exit(int status) {
        Shutdown.exit(status);
    }

    /** Ends the VM at once: no shutdown hooks. */
    public void halt(int status) {
        VM.exit(status);
    }

    public void addShutdownHook(Thread hook) {
        Shutdown.add(hook);
    }

    public boolean removeShutdownHook(Thread hook) {
        return Shutdown.remove(hook);
    }

    public void gc() {
        VM.gc();
    }

    public long freeMemory() {
        return VM.freeMemory();
    }

    public long totalMemory() {
        return VM.totalMemory();
    }

    public long maxMemory() {
        return VM.maxMemory();
    }

    public int availableProcessors() {
        return VM.availableProcessors();
    }
}
