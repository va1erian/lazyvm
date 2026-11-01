package lazy.internal;

import java.nio.CharBuffer;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * The int streams of a CharSequence. The sequence is not touched until the stream is first used, and it is read
 * through length() and charAt() only, in the exact pattern that docs/design/streams.md H3 pins; the walkers below
 * are written to produce those calls (a sequence may shrink or grow while it is being read).
 */
public final class TextStreams {
    private TextStreams() {
    }

    /** The UTF-16 units: sized when the stream first runs. */
    public static IntStream chars(CharSequence s) {
        Objects.requireNonNull(s);
        int ch = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
        if (s instanceof CharBuffer) {
            return StreamSupport.intStream(() -> snapshot((CharBuffer) s), ch, false);
        }
        return StreamSupport.intStream(() -> Spliterators.spliterator(new Units(s), s.length(),
                Spliterator.ORDERED), ch, false);
    }

    /** A buffer's chars are those between its position and limit when the stream first runs; moving it later changes nothing. */
    private static Spliterator.OfInt snapshot(CharBuffer buffer) {
        CharBuffer view = buffer.duplicate();
        return Spliterators.spliterator(new Cells(view), view.remaining(), Spliterator.ORDERED);
    }

    /** The code points: of unknown size, because the number of pairs is not known without reading. */
    public static IntStream codePoints(CharSequence s) {
        Objects.requireNonNull(s);
        return StreamSupport.intStream(() -> Spliterators.spliteratorUnknownSize(new Points(s),
                Spliterator.ORDERED), Spliterator.ORDERED, false);
    }

    /** Walks a private view of a buffer. */
    private static final class Cells implements PrimitiveIterator.OfInt {
        private final CharBuffer view;

        Cells(CharBuffer view) { this.view = view; }

        @Override
        public boolean hasNext() { return view.hasRemaining(); }

        @Override
        public int nextInt() {
            if (!view.hasRemaining()) {
                throw new NoSuchElementException();
            }
            return view.get();
        }
    }

    /** Walks the units, re-reading length() before every one. */
    private static final class Units implements PrimitiveIterator.OfInt {
        private final CharSequence seq;
        private int at;

        Units(CharSequence seq) { this.seq = seq; }

        @Override
        public boolean hasNext() { return at < seq.length(); }

        @Override
        public int nextInt() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return seq.charAt(at++);
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            while (at < seq.length()) {
                action.accept(seq.charAt(at++));
            }
        }
    }

    /** Walks the code points; a high surrogate takes the unit after it when that is a low surrogate. */
    private static final class Points implements PrimitiveIterator.OfInt {
        private final CharSequence seq;
        private int at;

        Points(CharSequence seq) { this.seq = seq; }

        @Override
        public boolean hasNext() { return at < seq.length(); }

        /** One code point, decided against the length `end` the caller read. */
        private int take(int end) {
            char first = seq.charAt(at++);
            if (Character.isHighSurrogate(first) && at < end) {
                char second = seq.charAt(at);
                if (Character.isLowSurrogate(second)) {
                    at++;
                    return Character.toCodePoint(first, second);
                }
            }
            return first;
        }

        @Override
        public int nextInt() {
            int end = seq.length();
            if (at >= end) {
                throw new NoSuchElementException();
            }
            return take(end);
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            int end = seq.length();
            while (at < end) {
                action.accept(take(end));
            }
        }
    }
}
