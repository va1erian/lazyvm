package java.util.logging;

import java.util.MissingResourceException;
import java.util.function.Supplier;

/** A named source of log records, arranged in a tree whose root holds the default handler. */
public class Logger {
    // Declared before `global`: building that logger already reads it.
    private static final Handler[] NO_HANDLERS = new Handler[0];

    public static final String GLOBAL_LOGGER_NAME = "global";
    @Deprecated
    public static final Logger global = new Logger(GLOBAL_LOGGER_NAME, null);

    static {
        // Starting the manager gives `global` its parent; it cannot happen earlier, see LogManager.start.
        LogManager.getLogManager();
    }

    /** Lets LogManager finish this class's initialization before it takes its own lock (no lock cycle). */
    static void ensureInitialized() {
    }

    private final String name;
    private volatile Level level;
    private volatile Filter filter;
    private volatile boolean useParent = true;
    private volatile Handler[] handlers = NO_HANDLERS;
    // Written by LogManager while it links the tree.
    volatile Logger parent;

    // Resource bundles are not part of LazyVM, so a bundle name can only fail.
    protected Logger(String name, String resourceBundleName) {
        if (resourceBundleName != null) {
            throw new MissingResourceException("Can't find " + resourceBundleName + " bundle", resourceBundleName, "");
        }
        this.name = name;
    }

    public static final Logger getGlobal() {
        return getLogger(GLOBAL_LOGGER_NAME);
    }

    public static Logger getLogger(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        return LogManager.getLogManager().demand(name);
    }

    public static Logger getLogger(String name, String resourceBundleName) {
        if (name == null) {
            throw new NullPointerException();
        }
        if (resourceBundleName != null) {
            new Logger(name, resourceBundleName);
        }
        return getLogger(name);
    }

    public static Logger getAnonymousLogger() {
        return getAnonymousLogger(null);
    }

    public static Logger getAnonymousLogger(String resourceBundleName) {
        Logger l = new Logger(null, resourceBundleName);
        l.parent = LogManager.getLogManager().rootLogger();
        return l;
    }

    public String getResourceBundleName() {
        return null;
    }

    public void setFilter(Filter newFilter) throws SecurityException {
        filter = newFilter;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setLevel(Level newLevel) throws SecurityException {
        level = newLevel;
    }

    public Level getLevel() {
        return level;
    }

    public String getName() {
        return name;
    }

    public boolean isLoggable(Level level) {
        if (level == null) {
            throw new NullPointerException();
        }
        int limit = effectiveValue();
        return level.intValue() >= limit && limit != Level.OFF.intValue();
    }

    // The nearest level set on this logger or above it; INFO when the chain sets none.
    private int effectiveValue() {
        for (Logger l = this; l != null; l = l.parent) {
            Level own = l.level;
            if (own != null) {
                return own.intValue();
            }
        }
        return Level.INFO.intValue();
    }

    public Logger getParent() {
        return parent;
    }

    public void setParent(Logger parent) {
        if (parent == null) {
            throw new NullPointerException();
        }
        this.parent = parent;
    }

    public void setUseParentHandlers(boolean useParentHandlers) {
        useParent = useParentHandlers;
    }

    public boolean getUseParentHandlers() {
        return useParent;
    }

    public synchronized void addHandler(Handler handler) throws SecurityException {
        if (handler == null) {
            throw new NullPointerException();
        }
        Handler[] old = handlers;
        Handler[] next = new Handler[old.length + 1];
        System.arraycopy(old, 0, next, 0, old.length);
        next[old.length] = handler;
        handlers = next;
    }

    public synchronized void removeHandler(Handler handler) throws SecurityException {
        Handler[] old = handlers;
        for (int i = 0; handler != null && i < old.length; i++) {
            if (old[i] == handler) {
                Handler[] next = new Handler[old.length - 1];
                System.arraycopy(old, 0, next, 0, i);
                System.arraycopy(old, i + 1, next, i, old.length - i - 1);
                handlers = next;
                return;
            }
        }
    }

    public Handler[] getHandlers() {
        return handlers.clone();
    }

    // Every logging method ends here, so a subclass overriding this sees them all. The walk reads the tree
    // through the public getters for the same reason; the level and the filter come from the fields.
    public void log(LogRecord record) {
        if (record == null) {
            throw new NullPointerException();
        }
        if (!isLoggable(record.getLevel())) {
            return;
        }
        Filter f = filter;
        if (f != null && !f.isLoggable(record)) {
            return;
        }
        for (Logger l = this; l != null; l = l.getParent()) {
            for (Handler h : l.getHandlers()) {
                h.publish(record);
            }
            if (!l.getUseParentHandlers()) {
                break;
            }
        }
    }

    private LogRecord record(Level level, String msg) {
        LogRecord r = new LogRecord(level, msg);
        r.setLoggerName(name);
        return r;
    }

    private LogRecord record(Level level, String msg, Object[] params, Throwable thrown) {
        LogRecord r = record(level, msg);
        r.setParameters(params);
        r.setThrown(thrown);
        return r;
    }

    private static LogRecord from(LogRecord r, String sourceClass, String sourceMethod) {
        r.setSourceClassName(sourceClass);
        r.setSourceMethodName(sourceMethod);
        return r;
    }

    private void emit(Level level, String msg, Object[] params, Throwable thrown) {
        if (isLoggable(level)) {
            log(record(level, msg, params, thrown));
        }
    }

    private void emitFrom(Level level, String cls, String method, String msg, Object[] params, Throwable thrown) {
        if (isLoggable(level)) {
            log(from(record(level, msg, params, thrown), cls, method));
        }
    }

    private void emit(Level level, Supplier<String> msg, Throwable thrown) {
        if (isLoggable(level)) {
            log(record(level, msg.get(), null, thrown));
        }
    }

    private void emitFrom(Level level, String cls, String method, Supplier<String> msg, Throwable thrown) {
        if (isLoggable(level)) {
            log(from(record(level, msg.get(), null, thrown), cls, method));
        }
    }

    public void log(Level level, String msg) {
        emit(level, msg, null, null);
    }

    public void log(Level level, Supplier<String> msgSupplier) {
        emit(level, msgSupplier, null);
    }

    public void log(Level level, String msg, Object param1) {
        emit(level, msg, new Object[] {param1}, null);
    }

    public void log(Level level, String msg, Object[] params) {
        emit(level, msg, params, null);
    }

    public void log(Level level, String msg, Throwable thrown) {
        emit(level, msg, null, thrown);
    }

    public void log(Level level, Throwable thrown, Supplier<String> msgSupplier) {
        emit(level, msgSupplier, thrown);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg) {
        emitFrom(level, sourceClass, sourceMethod, msg, null, null);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, Supplier<String> msgSupplier) {
        emitFrom(level, sourceClass, sourceMethod, msgSupplier, null);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Object param1) {
        emitFrom(level, sourceClass, sourceMethod, msg, new Object[] {param1}, null);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Object[] params) {
        emitFrom(level, sourceClass, sourceMethod, msg, params, null);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Throwable thrown) {
        emitFrom(level, sourceClass, sourceMethod, msg, null, thrown);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, Throwable thrown,
            Supplier<String> msgSupplier) {
        emitFrom(level, sourceClass, sourceMethod, msgSupplier, thrown);
    }

    public void entering(String sourceClass, String sourceMethod) {
        logp(Level.FINER, sourceClass, sourceMethod, "ENTRY");
    }

    public void entering(String sourceClass, String sourceMethod, Object param1) {
        logp(Level.FINER, sourceClass, sourceMethod, "ENTRY {0}", param1);
    }

    // A null array takes the plain ENTRY route; a real one builds its message only when it will be logged.
    public void entering(String sourceClass, String sourceMethod, Object[] params) {
        if (params == null) {
            logp(Level.FINER, sourceClass, sourceMethod, "ENTRY");
            return;
        }
        if (!isLoggable(Level.FINER)) {
            return;
        }
        StringBuilder msg = new StringBuilder("ENTRY");
        for (int i = 0; i < params.length; i++) {
            msg.append(" {").append(i).append('}');
        }
        logp(Level.FINER, sourceClass, sourceMethod, msg.toString(), params);
    }

    public void exiting(String sourceClass, String sourceMethod) {
        logp(Level.FINER, sourceClass, sourceMethod, "RETURN");
    }

    public void exiting(String sourceClass, String sourceMethod, Object result) {
        logp(Level.FINER, sourceClass, sourceMethod, "RETURN {0}", result);
    }

    public void throwing(String sourceClass, String sourceMethod, Throwable thrown) {
        emitFrom(Level.FINER, sourceClass, sourceMethod, "THROW", null, thrown);
    }

    public void severe(String msg) {
        log(Level.SEVERE, msg);
    }

    public void warning(String msg) {
        log(Level.WARNING, msg);
    }

    public void info(String msg) {
        log(Level.INFO, msg);
    }

    public void config(String msg) {
        log(Level.CONFIG, msg);
    }

    public void fine(String msg) {
        log(Level.FINE, msg);
    }

    public void finer(String msg) {
        log(Level.FINER, msg);
    }

    public void finest(String msg) {
        log(Level.FINEST, msg);
    }

    public void severe(Supplier<String> msgSupplier) {
        log(Level.SEVERE, msgSupplier);
    }

    public void warning(Supplier<String> msgSupplier) {
        log(Level.WARNING, msgSupplier);
    }

    public void info(Supplier<String> msgSupplier) {
        log(Level.INFO, msgSupplier);
    }

    public void config(Supplier<String> msgSupplier) {
        log(Level.CONFIG, msgSupplier);
    }

    public void fine(Supplier<String> msgSupplier) {
        log(Level.FINE, msgSupplier);
    }

    public void finer(Supplier<String> msgSupplier) {
        log(Level.FINER, msgSupplier);
    }

    public void finest(Supplier<String> msgSupplier) {
        log(Level.FINEST, msgSupplier);
    }
}
