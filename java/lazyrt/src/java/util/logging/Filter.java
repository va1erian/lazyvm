package java.util.logging;

/** Decides whether a record is published. */
@FunctionalInterface
public interface Filter {
    boolean isLoggable(LogRecord record);
}
