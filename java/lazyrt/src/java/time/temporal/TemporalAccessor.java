package java.time.temporal;

/** Read access to the fields of a date-time. Only the two members the formatter needs. */
public interface TemporalAccessor {
    boolean isSupported(TemporalField field);

    long getLong(TemporalField field);
}
