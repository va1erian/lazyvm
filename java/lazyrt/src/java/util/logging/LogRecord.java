package java.util.logging;

/** One logging event. */
public class LogRecord implements java.io.Serializable {
    private static final String LOGGER_CLASS = "java.util.logging.Logger";
    // One counter for the whole process, so sequence numbers order records across threads.
    private static long counter;

    private Level level;
    private long sequence;
    private String sourceClass;
    private String sourceMethod;
    // Set until the source was given or worked out; working it out waits for the first read because the
    // stack that names the caller is only there while the logger is still publishing.
    private boolean sourcePending = true;
    private String message;
    private int threadId;
    private long millis;
    private Throwable thrown;
    private String loggerName;
    private String bundleName;
    private Object[] parameters;

    public LogRecord(Level level, String msg) {
        if (level == null) {
            throw new NullPointerException();
        }
        this.level = level;
        this.message = msg;
        this.sequence = nextSequence();
        this.threadId = (int) Thread.currentThread().getId();
        this.millis = System.currentTimeMillis();
    }

    private static synchronized long nextSequence() {
        return counter++;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(String name) {
        loggerName = name;
    }

    public String getResourceBundleName() {
        return bundleName;
    }

    public void setResourceBundleName(String name) {
        bundleName = name;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        if (level == null) {
            throw new NullPointerException();
        }
        this.level = level;
    }

    public long getSequenceNumber() {
        return sequence;
    }

    public void setSequenceNumber(long seq) {
        sequence = seq;
    }

    public String getSourceClassName() {
        inferSource();
        return sourceClass;
    }

    public void setSourceClassName(String sourceClassName) {
        sourcePending = false;
        sourceClass = sourceClassName;
    }

    public String getSourceMethodName() {
        inferSource();
        return sourceMethod;
    }

    public void setSourceMethodName(String sourceMethodName) {
        sourcePending = false;
        sourceMethod = sourceMethodName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object[] getParameters() {
        return parameters;
    }

    public void setParameters(Object[] parameters) {
        this.parameters = parameters;
    }

    public int getThreadID() {
        return threadId;
    }

    public void setThreadID(int threadID) {
        threadId = threadID;
    }

    public long getMillis() {
        return millis;
    }

    public void setMillis(long millis) {
        this.millis = millis;
    }

    public Throwable getThrown() {
        return thrown;
    }

    public void setThrown(Throwable thrown) {
        this.thrown = thrown;
    }

    // The caller is the first frame outside the run of Logger frames nearest the top of the stack, passing
    // over the glue between a logger and its caller: generated lambda classes and reflection.
    // With no Logger frame at all (a record built by hand) both names stay null for good.
    private void inferSource() {
        if (!sourcePending) {
            return;
        }
        sourcePending = false;
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int i = 0;
        while (i < stack.length && !stack[i].getClassName().equals(LOGGER_CLASS)) {
            i++;
        }
        while (i < stack.length && stack[i].getClassName().equals(LOGGER_CLASS)) {
            i++;
        }
        while (i < stack.length && isGlue(stack[i].getClassName())) {
            i++;
        }
        if (i < stack.length) {
            sourceClass = stack[i].getClassName();
            sourceMethod = stack[i].getMethodName();
        }
    }

    // "-Lambda-" is how LazyVM names the class behind a lambda or method reference; "$$Lambda$" and the
    // reflection packages are the other runtimes' spellings of the same kind of frame.
    private static boolean isGlue(String cls) {
        return cls.contains("-Lambda-") || cls.contains("$$Lambda$") || cls.startsWith("java.lang.reflect.")
                || cls.startsWith("sun.reflect.");
    }
}
