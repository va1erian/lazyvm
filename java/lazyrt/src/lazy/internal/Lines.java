package lazy.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** The lines of a reader, read one at a time from the reader itself so other readers of it stay in step. */
public final class Lines implements Iterator<String> {
    private final BufferedReader reader;
    private String next;
    private boolean done;

    private Lines(BufferedReader reader) {
        this.reader = reader;
    }

    public static Stream<String> stream(BufferedReader reader) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(new Lines(reader),
                Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        if (done) {
            return false;
        }
        try {
            next = reader.readLine();
            if (next == null) {
                done = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return next != null;
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        String line = next;
        next = null;
        return line;
    }
}
