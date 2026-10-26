package java.lang;

/** The growable char buffer behind StringBuilder and StringBuffer. */
abstract class AbstractStringBuilder implements Appendable, CharSequence {
    char[] value;
    int count;

    AbstractStringBuilder(int capacity) {
        if (capacity < 0) {
            throw new NegativeArraySizeException(String.valueOf(capacity));
        }
        value = new char[capacity];
    }

    public int length() {
        return count;
    }

    public int capacity() {
        return value.length;
    }

    public void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity > value.length) {
            grow(minimumCapacity);
        }
    }

    private void grow(int minimum) {
        int size = value.length * 2 + 2;
        if (size < minimum || size < 0) {
            size = minimum;
        }
        char[] bigger = new char[size];
        System.arraycopy(value, 0, bigger, 0, count);
        value = bigger;
    }

    public void trimToSize() {
        if (count < value.length) {
            char[] smaller = new char[count];
            System.arraycopy(value, 0, smaller, 0, count);
            value = smaller;
        }
    }

    public void setLength(int newLength) {
        if (newLength < 0) {
            throw new StringIndexOutOfBoundsException(newLength);
        }
        ensureCapacity(newLength);
        for (int i = count; i < newLength; i++) {
            value[i] = '\0';
        }
        count = newLength;
    }

    public char charAt(int index) {
        if (index < 0 || index >= count) {
            throw new StringIndexOutOfBoundsException("index " + index + ",length " + count);
        }
        return value[index];
    }

    public int codePointAt(int index) {
        if (index < 0 || index >= count) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return Character.codePointAt(value, index, count);
    }

    public int codePointBefore(int index) {
        if (index < 1 || index > count) {
            throw new StringIndexOutOfBoundsException(index);
        }
        char low = value[index - 1];
        if (Character.isLowSurrogate(low) && index >= 2 && Character.isHighSurrogate(value[index - 2])) {
            return Character.toCodePoint(value[index - 2], low);
        }
        return low;
    }

    public int codePointCount(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > count || beginIndex > endIndex) {
            throw new IndexOutOfBoundsException();
        }
        int n = endIndex - beginIndex;
        for (int i = beginIndex; i < endIndex - 1; i++) {
            if (Character.isHighSurrogate(value[i]) && Character.isLowSurrogate(value[i + 1])) {
                n--;
                i++;
            }
        }
        return n;
    }

    public void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        if (srcBegin < 0) {
            throw new StringIndexOutOfBoundsException(srcBegin);
        }
        if (srcEnd < 0 || srcEnd > count) {
            throw new StringIndexOutOfBoundsException(srcEnd);
        }
        if (srcBegin > srcEnd) {
            throw new StringIndexOutOfBoundsException("srcBegin > srcEnd");
        }
        System.arraycopy(value, srcBegin, dst, dstBegin, srcEnd - srcBegin);
    }

    public void setCharAt(int index, char ch) {
        if (index < 0 || index >= count) {
            throw new StringIndexOutOfBoundsException(index);
        }
        value[index] = ch;
    }

    public AbstractStringBuilder append(Object obj) {
        return append(String.valueOf(obj));
    }

    public AbstractStringBuilder append(String str) {
        if (str == null) {
            str = "null";
        }
        int len = str.length();
        ensureCapacity(count + len);
        str.getChars(0, len, value, count);
        count += len;
        return this;
    }

    public AbstractStringBuilder append(CharSequence s) {
        if (s == null) {
            return append("null");
        }
        return append(s, 0, s.length());
    }

    public AbstractStringBuilder append(CharSequence s, int start, int end) {
        if (s == null) {
            s = "null";
        }
        if (start < 0 || start > end || end > s.length()) {
            throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", length "
                    + s.length());
        }
        ensureCapacity(count + (end - start));
        for (int i = start; i < end; i++) {
            value[count++] = s.charAt(i);
        }
        return this;
    }

    public AbstractStringBuilder append(char[] str) {
        return append(str, 0, str.length);
    }

    public AbstractStringBuilder append(char[] str, int offset, int len) {
        if (offset < 0 || len < 0 || offset > str.length - len) {
            throw new IndexOutOfBoundsException("offset " + offset + ", count " + len + ", length "
                    + str.length);
        }
        ensureCapacity(count + len);
        System.arraycopy(str, offset, value, count, len);
        count += len;
        return this;
    }

    public AbstractStringBuilder append(boolean b) {
        return append(b ? "true" : "false");
    }

    public AbstractStringBuilder append(char c) {
        ensureCapacity(count + 1);
        value[count++] = c;
        return this;
    }

    public AbstractStringBuilder append(int i) {
        return append(Integer.toString(i));
    }

    public AbstractStringBuilder append(long l) {
        return append(Long.toString(l));
    }

    public AbstractStringBuilder append(float f) {
        return append(Float.toString(f));
    }

    public AbstractStringBuilder append(double d) {
        return append(Double.toString(d));
    }

    public AbstractStringBuilder appendCodePoint(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException();
        }
        if (Character.isBmpCodePoint(codePoint)) {
            return append((char) codePoint);
        }
        append(Character.highSurrogate(codePoint));
        return append(Character.lowSurrogate(codePoint));
    }

    public AbstractStringBuilder delete(int start, int end) {
        if (end > count) {
            end = count;
        }
        if (start < 0 || start > end) {
            throw new StringIndexOutOfBoundsException("start " + start + ", end " + end
                    + ", length " + count);
        }
        System.arraycopy(value, end, value, start, count - end);
        count -= end - start;
        return this;
    }

    public AbstractStringBuilder deleteCharAt(int index) {
        if (index < 0 || index >= count) {
            throw new StringIndexOutOfBoundsException(index);
        }
        System.arraycopy(value, index + 1, value, index, count - index - 1);
        count--;
        return this;
    }

    public AbstractStringBuilder replace(int start, int end, String str) {
        if (end > count) {
            end = count;
        }
        if (start < 0 || start > count || start > end) {
            throw new StringIndexOutOfBoundsException("start " + start + ", end " + end
                    + ", length " + count);
        }
        int len = str.length();
        int newCount = count + len - (end - start);
        ensureCapacity(newCount);
        System.arraycopy(value, end, value, start + len, count - end);
        str.getChars(0, len, value, start);
        count = newCount;
        return this;
    }

    public String substring(int start) {
        return substring(start, count);
    }

    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    public String substring(int start, int end) {
        if (start < 0 || end > count || start > end) {
            throw new StringIndexOutOfBoundsException("start " + start + ", end " + end
                    + ", length " + count);
        }
        return new String(value, start, end - start);
    }

    private void checkOffset(int offset) {
        if (offset < 0 || offset > count) {
            throw new StringIndexOutOfBoundsException("offset " + offset + ", length " + count);
        }
    }

    /** Opens a gap of `len` chars at `offset` and returns after the length was updated. */
    private void openGap(int offset, int len) {
        ensureCapacity(count + len);
        System.arraycopy(value, offset, value, offset + len, count - offset);
        count += len;
    }

    public AbstractStringBuilder insert(int index, char[] str, int offset, int len) {
        checkOffset(index);
        if (offset < 0 || len < 0 || offset > str.length - len) {
            throw new StringIndexOutOfBoundsException("offset " + offset + ", len " + len
                    + ", str.length " + str.length);
        }
        openGap(index, len);
        System.arraycopy(str, offset, value, index, len);
        return this;
    }

    public AbstractStringBuilder insert(int offset, Object obj) {
        return insert(offset, String.valueOf(obj));
    }

    public AbstractStringBuilder insert(int offset, String str) {
        checkOffset(offset);
        if (str == null) {
            str = "null";
        }
        int len = str.length();
        openGap(offset, len);
        str.getChars(0, len, value, offset);
        return this;
    }

    public AbstractStringBuilder insert(int offset, char[] str) {
        return insert(offset, str, 0, str.length);
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s) {
        return insert(dstOffset, String.valueOf(s));
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        if (s == null) {
            s = "null";
        }
        checkOffset(dstOffset);
        if (start < 0 || start > end || end > s.length()) {
            throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", length "
                    + s.length());
        }
        int len = end - start;
        openGap(dstOffset, len);
        for (int i = 0; i < len; i++) {
            value[dstOffset + i] = s.charAt(start + i);
        }
        return this;
    }

    public AbstractStringBuilder insert(int offset, boolean b) {
        return insert(offset, String.valueOf(b));
    }

    public AbstractStringBuilder insert(int offset, char c) {
        checkOffset(offset);
        openGap(offset, 1);
        value[offset] = c;
        return this;
    }

    public AbstractStringBuilder insert(int offset, int i) {
        return insert(offset, String.valueOf(i));
    }

    public AbstractStringBuilder insert(int offset, long l) {
        return insert(offset, String.valueOf(l));
    }

    public AbstractStringBuilder insert(int offset, float f) {
        return insert(offset, String.valueOf(f));
    }

    public AbstractStringBuilder insert(int offset, double d) {
        return insert(offset, String.valueOf(d));
    }

    public int indexOf(String str) {
        return indexOf(str, 0);
    }

    public int indexOf(String str, int fromIndex) {
        return toString().indexOf(str, fromIndex);
    }

    public int lastIndexOf(String str) {
        return lastIndexOf(str, count);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return toString().lastIndexOf(str, fromIndex);
    }

    public AbstractStringBuilder reverse() {
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            char t = value[i];
            value[i] = value[j];
            value[j] = t;
        }
        // The reversal flipped every surrogate pair; put those back in order.
        for (int i = 0; i < count - 1; i++) {
            if (Character.isLowSurrogate(value[i]) && Character.isHighSurrogate(value[i + 1])) {
                char t = value[i];
                value[i] = value[i + 1];
                value[i + 1] = t;
                i++;
            }
        }
        return this;
    }

    public abstract String toString();
}
