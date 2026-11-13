package lazy.msg;

import java.nio.ByteBuffer;

/**
 * A shared buffer a request carries (MIDL's {@code Buffer}): pages both sides see, and the range
 * of them this message means. The sender keeps its view; the receiver gets one over the same
 * bytes.
 */
public final class Buffer {
    private final ByteBuffer pages;
    private final long offset, length;

    private Buffer(ByteBuffer pages, long offset, long length) {
        this.pages = pages;
        this.offset = offset;
        this.length = length;
    }

    /** A new buffer of {@code size} bytes, all of it in range. */
    public static Buffer allocate(int size) {
        return new Buffer(ByteBuffer.allocateDirect(size), 0, size);
    }

    /** The same pages with another range; it must lie inside them. */
    public Buffer range(long offset, long length) {
        if (offset < 0 || length < 0 || offset > pages.capacity() || length > pages.capacity() - offset) {
            throw new IllegalArgumentException("range " + offset + "+" + length + " outside " + pages.capacity());
        }
        return new Buffer(pages, offset, length);
    }

    /** The range as a buffer of its own, position 0. */
    public ByteBuffer bytes() {
        ByteBuffer view = pages.duplicate();
        view.limit((int) (offset + length)).position((int) offset);
        return view.slice();
    }

    public long offset() {
        return offset;
    }

    public long length() {
        return length;
    }
}
