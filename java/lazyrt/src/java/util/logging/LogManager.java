package java.util.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;

/**
 * The registry of named loggers and the root of their tree. There is no configuration file: the root
 * logger starts at INFO with a console handler, and everything else is set from the program.
 */
public class LogManager {
    private static final LogManager manager = new LogManager();

    // Sorted, so the loggers below a name are one contiguous range (see link).
    private final TreeMap<String, Logger> registry = new TreeMap<String, Logger>();
    private boolean started;
    private Logger root;

    protected LogManager() {
    }

    public static LogManager getLogManager() {
        // Logger's initializer calls back here under its class-init lock, and start() needs this object's
        // monitor; finishing Logger's initialization first keeps the two locks from ever being taken in
        // opposite orders.
        Logger.ensureInitialized();
        manager.start();
        return manager;
    }

    // Built on first use rather than in the class initializer, because making the root logger runs
    // Logger's own initializer, which asks for the manager. A second entry from that initializer on the
    // same thread finds `started` set and returns; the thread that began finishes the job.
    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        root = new Logger("", null);
        root.setLevel(Level.INFO);
        root.addHandler(new ConsoleHandler());
        registry.put("", root);
        registry.put(Logger.GLOBAL_LOGGER_NAME, Logger.global);
        Logger.global.parent = root;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> reset()));
        } catch (IllegalStateException e) {
            // The VM is already shutting down; the handlers are being closed anyway.
        }
    }

    synchronized Logger rootLogger() {
        return root;
    }

    public synchronized boolean addLogger(Logger logger) {
        String name = logger.getName();
        if (name == null) {
            throw new NullPointerException();
        }
        if (registry.containsKey(name)) {
            return false;
        }
        registry.put(name, logger);
        link(logger);
        return true;
    }

    public synchronized Logger getLogger(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        return registry.get(name);
    }

    // What Logger.getLogger does: the registered logger, or a new one entered into the tree.
    synchronized Logger demand(String name) {
        Logger found = registry.get(name);
        if (found == null) {
            found = new Logger(name, null);
            addLogger(found);
        }
        return found;
    }

    public synchronized Enumeration<String> getLoggerNames() {
        return Collections.enumeration(new ArrayList<String>(registry.keySet()));
    }

    // Handlers are closed with no lock held: a close() may create loggers or wait for another thread that
    // is using this manager. The loggers are a snapshot; ones registered meanwhile are left alone.
    public void reset() throws SecurityException {
        ArrayList<Logger> all;
        Logger top;
        synchronized (this) {
            all = new ArrayList<Logger>(registry.values());
            top = root;
        }
        for (Logger l : all) {
            for (Handler h : l.getHandlers()) {
                l.removeHandler(h);
                closeQuietly(h);
            }
            l.setLevel(l == top ? Level.INFO : null);
        }
    }

    private static void closeQuietly(Handler h) {
        try {
            h.close();
        } catch (Exception e) {
            // One handler failing must not keep the others from closing.
        }
    }

    // Parent of a newly registered logger is its nearest registered dotted prefix, else the root. Loggers
    // below it whose nearest registered prefix is now this one move to it, an explicit setParent included.
    private void link(Logger logger) {
        String name = logger.getName();
        logger.parent = nearest(name);
        for (Map.Entry<String, Logger> e : registry.subMap(name + ".", name + "/").entrySet()) {
            if (nearest(e.getKey()) == logger) {
                e.getValue().parent = logger;
            }
        }
    }

    // The registered logger named by the longest proper dotted prefix of name, else the root.
    private Logger nearest(String name) {
        for (int cut = name.lastIndexOf('.'); cut > 0; cut = name.lastIndexOf('.', cut - 1)) {
            Logger candidate = registry.get(name.substring(0, cut));
            if (candidate != null) {
                return candidate;
            }
        }
        return root;
    }
}
