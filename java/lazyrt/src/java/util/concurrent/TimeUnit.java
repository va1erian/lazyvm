package java.util.concurrent;

/** Units of time, each a whole number of nanoseconds. */
public enum TimeUnit {
    NANOSECONDS(1L),
    MICROSECONDS(1000L),
    MILLISECONDS(1000000L),
    SECONDS(1000000000L),
    MINUTES(60000000000L),
    HOURS(3600000000000L),
    DAYS(86400000000000L);

    private final long nanos;

    TimeUnit(long nanos) {
        this.nanos = nanos;
    }

    /** `d` of `from` in this unit: truncated toward zero, saturated on overflow (Javadoc). */
    public long convert(long d, TimeUnit from) {
        if (from.nanos == nanos) {
            return d;
        }
        if (from.nanos < nanos) {
            return d / (nanos / from.nanos);
        }
        long factor = from.nanos / nanos;
        long limit = Long.MAX_VALUE / factor;
        if (d > limit) {
            return Long.MAX_VALUE;
        }
        if (d < -limit) {
            return Long.MIN_VALUE;
        }
        return d * factor;
    }

    public long toNanos(long d) {
        return NANOSECONDS.convert(d, this);
    }

    public long toMicros(long d) {
        return MICROSECONDS.convert(d, this);
    }

    public long toMillis(long d) {
        return MILLISECONDS.convert(d, this);
    }

    public long toSeconds(long d) {
        return SECONDS.convert(d, this);
    }

    public long toMinutes(long d) {
        return MINUTES.convert(d, this);
    }

    public long toHours(long d) {
        return HOURS.convert(d, this);
    }

    public long toDays(long d) {
        return DAYS.convert(d, this);
    }

    public void timedWait(Object obj, long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            obj.wait(millis, nanosBeyond(timeout, millis));
        }
    }

    public void timedJoin(Thread thread, long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            thread.join(millis, nanosBeyond(timeout, millis));
        }
    }

    public void sleep(long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            Thread.sleep(millis, nanosBeyond(timeout, millis));
        }
    }

    /** What truncating `d` of this unit to `millis` lost; only units below a millisecond lose any. */
    private int nanosBeyond(long d, long millis) {
        return nanos >= 1000000L ? 0 : (int) (d * nanos - millis * 1000000L);
    }
}
