package java.lang;

public final class StringBuffer extends AbstractStringBuilder implements java.io.Serializable, CharSequence {
    public StringBuffer() {
        super(16);
    }

    public StringBuffer(int capacity) {
        super(capacity);
    }

    public StringBuffer(String str) {
        super(str.length() + 16);
        append(str);
    }

    public StringBuffer(CharSequence seq) {
        super(seq.length() + 16);
        append(seq);
    }

    public synchronized StringBuffer append(Object obj) {
        super.append(obj);
        return this;
    }

    public synchronized StringBuffer append(String str) {
        super.append(str);
        return this;
    }

    public synchronized StringBuffer append(StringBuffer sb) {
        super.append((CharSequence) sb);
        return this;
    }

    public synchronized StringBuffer append(CharSequence s) {
        super.append(s);
        return this;
    }

    public synchronized StringBuffer append(char[] str) {
        super.append(str);
        return this;
    }

    public synchronized StringBuffer append(boolean b) {
        super.append(b);
        return this;
    }

    public synchronized StringBuffer append(char c) {
        super.append(c);
        return this;
    }

    public synchronized StringBuffer append(int i) {
        super.append(i);
        return this;
    }

    public synchronized StringBuffer append(long l) {
        super.append(l);
        return this;
    }

    public synchronized StringBuffer append(float f) {
        super.append(f);
        return this;
    }

    public synchronized StringBuffer append(double d) {
        super.append(d);
        return this;
    }

    public synchronized StringBuffer append(CharSequence s, int start, int end) {
        super.append(s, start, end);
        return this;
    }

    public synchronized StringBuffer append(char[] str, int offset, int len) {
        super.append(str, offset, len);
        return this;
    }

    public synchronized StringBuffer appendCodePoint(int codePoint) {
        super.appendCodePoint(codePoint);
        return this;
    }

    public synchronized StringBuffer delete(int start, int end) {
        super.delete(start, end);
        return this;
    }

    public synchronized StringBuffer deleteCharAt(int index) {
        super.deleteCharAt(index);
        return this;
    }

    public synchronized StringBuffer replace(int start, int end, String str) {
        super.replace(start, end, str);
        return this;
    }

    public synchronized StringBuffer insert(int index, char[] str, int offset, int len) {
        super.insert(index, str, offset, len);
        return this;
    }

    public synchronized StringBuffer insert(int offset, Object obj) {
        super.insert(offset, obj);
        return this;
    }

    public synchronized StringBuffer insert(int offset, String str) {
        super.insert(offset, str);
        return this;
    }

    public synchronized StringBuffer insert(int offset, char[] str) {
        super.insert(offset, str);
        return this;
    }

    public synchronized StringBuffer insert(int offset, CharSequence s) {
        super.insert(offset, s);
        return this;
    }

    public synchronized StringBuffer insert(int offset, boolean b) {
        super.insert(offset, b);
        return this;
    }

    public synchronized StringBuffer insert(int offset, char c) {
        super.insert(offset, c);
        return this;
    }

    public synchronized StringBuffer insert(int offset, int i) {
        super.insert(offset, i);
        return this;
    }

    public synchronized StringBuffer insert(int offset, long l) {
        super.insert(offset, l);
        return this;
    }

    public synchronized StringBuffer insert(int offset, float f) {
        super.insert(offset, f);
        return this;
    }

    public synchronized StringBuffer insert(int offset, double d) {
        super.insert(offset, d);
        return this;
    }

    public synchronized StringBuffer insert(int dstOffset, CharSequence s, int start, int end) {
        super.insert(dstOffset, s, start, end);
        return this;
    }

    public synchronized int indexOf(String str) {
        return super.indexOf(str);
    }

    public synchronized int indexOf(String str, int fromIndex) {
        return super.indexOf(str, fromIndex);
    }

    public synchronized int lastIndexOf(String str) {
        return super.lastIndexOf(str);
    }

    public synchronized int lastIndexOf(String str, int fromIndex) {
        return super.lastIndexOf(str, fromIndex);
    }

    public synchronized StringBuffer reverse() {
        super.reverse();
        return this;
    }

    public synchronized int length() {
        return super.length();
    }

    public synchronized int capacity() {
        return super.capacity();
    }

    public synchronized void ensureCapacity(int minimumCapacity) {
        super.ensureCapacity(minimumCapacity);
    }

    public synchronized void trimToSize() {
        super.trimToSize();
    }

    public synchronized void setLength(int newLength) {
        super.setLength(newLength);
    }

    public synchronized char charAt(int index) {
        return super.charAt(index);
    }

    public synchronized int codePointAt(int index) {
        return super.codePointAt(index);
    }

    public synchronized int codePointBefore(int index) {
        return super.codePointBefore(index);
    }

    public synchronized int codePointCount(int beginIndex, int endIndex) {
        return super.codePointCount(beginIndex, endIndex);
    }

    public synchronized void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        super.getChars(srcBegin, srcEnd, dst, dstBegin);
    }

    public synchronized void setCharAt(int index, char ch) {
        super.setCharAt(index, ch);
    }

    public synchronized String substring(int start) {
        return super.substring(start);
    }

    public synchronized String substring(int start, int end) {
        return super.substring(start, end);
    }

    public synchronized CharSequence subSequence(int start, int end) {
        return super.subSequence(start, end);
    }

    public synchronized String toString() {
        return new String(value, 0, count);
    }
}
