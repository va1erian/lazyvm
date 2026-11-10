package java.lang;

import java.util.stream.IntStream;

public interface CharSequence {
    int length();

    char charAt(int index);

    CharSequence subSequence(int start, int end);

    String toString();

    default IntStream chars() {
        return lazy.internal.TextStreams.chars(this);
    }

    default IntStream codePoints() {
        return lazy.internal.TextStreams.codePoints(this);
    }
}
