package java.io;

import lazy.internal.FileSystem;

/** A file read and written at a movable pointer (io.md FS4), in DataFormat's layouts. */
public class RandomAccessFile implements DataOutput, DataInput, Closeable {
    private final long handle;
    /** "rws" and "rwd": every write reaches the device before it returns. */
    private final boolean sync;
    private final byte[] scratch = new byte[8];

    // Spec FS4: the four modes, and the message for any other.
    public RandomAccessFile(String name, String mode) throws FileNotFoundException {
        if (!mode.equals("r") && !mode.equals("rw") && !mode.equals("rws") && !mode.equals("rwd")) {
            throw new IllegalArgumentException("Illegal mode \"" + mode
                    + "\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
        }
        if (name == null) {
            throw new NullPointerException();
        }
        handle = FileSystem.open(name, mode.equals("r") ? FileSystem.READ : FileSystem.READ_WRITE);
        sync = mode.length() == 3;
    }

    public RandomAccessFile(File file, String mode) throws FileNotFoundException {
        this(file == null ? null : file.getPath(), mode);
    }

    public int read() throws IOException {
        return FileSystem.read(handle, scratch, 0, 1) < 0 ? -1 : scratch[0] & 0xff;
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return FileSystem.read(handle, b, off, len);
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public final void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    // Spec FS4: the end before `len` bytes is EOFException, the pointer after what was read.
    public final void readFully(byte[] b, int off, int len) throws IOException {
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        int n = 0;
        while (n < len) {
            int k = read(b, off + n, len - n);
            if (k < 0) {
                throw new EOFException();
            }
            n += k;
        }
    }

    // Spec FS4: clamps at the end; n <= 0 skips nothing.
    public int skipBytes(int n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long pos = getFilePointer();
        long to = Math.min(pos + n, length());
        seek(to);
        return (int) (to - pos);
    }

    public void write(int b) throws IOException {
        scratch[0] = (byte) b;
        write(scratch, 0, 1);
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        FileSystem.write(handle, b, off, len);
        if (sync) {
            FileSystem.sync(handle);
        }
    }

    public long getFilePointer() throws IOException {
        return FileSystem.position(handle);
    }

    // Spec FS4: past the end keeps the length; a write there fills the gap with zeros.
    public void seek(long pos) throws IOException {
        FileSystem.seek(handle, pos);
    }

    public long length() throws IOException {
        return FileSystem.size(handle);
    }

    // Spec FS4: shrinking pulls the pointer back to the new end (the native moves it, files.md).
    public void setLength(long newLength) throws IOException {
        FileSystem.truncate(handle, newLength);
    }

    public void close() throws IOException {
        FileSystem.close(handle);
    }

    private int next() throws IOException {
        int c = read();
        if (c < 0) {
            throw new EOFException();
        }
        return c;
    }

    private long readBits(int n) throws IOException {
        readFully(scratch, 0, n);
        return DataFormat.bits(scratch, n);
    }

    private void writeBits(long v, int n) throws IOException {
        byte[] b = new byte[n];
        DataFormat.putBits(b, v, n);
        write(b, 0, n);
    }

    public final boolean readBoolean() throws IOException {
        return next() != 0;
    }

    public final byte readByte() throws IOException {
        return (byte) next();
    }

    public final int readUnsignedByte() throws IOException {
        return next();
    }

    public final short readShort() throws IOException {
        return (short) readBits(2);
    }

    public final int readUnsignedShort() throws IOException {
        return (int) readBits(2);
    }

    public final char readChar() throws IOException {
        return (char) readBits(2);
    }

    public final int readInt() throws IOException {
        return (int) readBits(4);
    }

    public final long readLong() throws IOException {
        return readBits(8);
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    // Spec FS4: Latin-1 bytes up to L1's terminators; a byte after '\r' is given back by seeking.
    public final String readLine() throws IOException {
        return DataFormat.readLine(new DataFormat.LineSource() {
            public int read() throws IOException {
                return RandomAccessFile.this.read();
            }

            public void unread() throws IOException {
                seek(getFilePointer() - 1);
            }
        });
    }

    public final String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
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

    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        write(DataFormat.lowBytes(s));
    }

    public final void writeChars(String s) throws IOException {
        write(DataFormat.chars(s));
    }

    public final void writeUTF(String str) throws IOException {
        write(DataFormat.modifiedUtf8(str));
    }
}
