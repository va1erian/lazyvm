package java.io;

/** Primitives as big-endian bytes and strings as modified UTF-8 (io.md D1, D2). */
public class DataOutputStream extends FilterOutputStream implements DataOutput {
    protected int written;
    private final byte[] scratch = new byte[8];

    public DataOutputStream(OutputStream out) {
        super(out);
    }

    private void count(int n) {
        written = written + n < 0 ? Integer.MAX_VALUE : written + n;
    }

    public synchronized void write(int b) throws IOException {
        out.write(b);
        count(1);
    }

    public synchronized void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        count(len);
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void writeBits(long v, int n) throws IOException {
        DataFormat.putBits(scratch, v, n);
        write(scratch, 0, n);
    }

    public final void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    public final void writeByte(int v) throws IOException {
        write(v);
    }

    public final void writeShort(int v) throws IOException {
        writeBits(v, 2);
    }

    public final void writeChar(int v) throws IOException {
        writeBits(v, 2);
    }

    public final void writeInt(int v) throws IOException {
        writeBits(v, 4);
    }

    public final void writeLong(long v) throws IOException {
        writeBits(v, 8);
    }

    // Spec D1: the canonical NaN.
    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        byte[] b = DataFormat.lowBytes(s);
        write(b, 0, b.length);
    }

    public final void writeChars(String s) throws IOException {
        byte[] b = DataFormat.chars(s);
        write(b, 0, b.length);
    }

    public final void writeUTF(String str) throws IOException {
        byte[] b = DataFormat.modifiedUtf8(str);
        write(b, 0, b.length);
    }

    public final int size() {
        return written;
    }
}
