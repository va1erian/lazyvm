package java.lang;

import java.io.UnsupportedEncodingException;
import java.util.Comparator;
import java.util.Iterator;

public final class String implements java.io.Serializable, Comparable<String>, CharSequence {
    private final char[] value;
    private int hash;

    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static final class CaseInsensitiveComparator
            implements Comparator<String>, java.io.Serializable {
        public int compare(String a, String b) {
            return a.compareToIgnoreCase(b);
        }
    }

    public String() {
        value = new char[0];
    }

    public String(String original) {
        value = original.value;
        hash = original.hash;
    }

    public String(char[] chars) {
        this(chars, 0, chars.length);
    }

    public String(char[] chars, int offset, int count) {
        checkRange(offset, count, chars.length);
        value = new char[count];
        System.arraycopy(chars, offset, value, 0, count);
    }

    public String(int[] codePoints, int offset, int count) {
        checkRange(offset, count, codePoints.length);
        int n = 0;
        for (int i = offset; i < offset + count; i++) {
            int cp = codePoints[i];
            if (!Character.isValidCodePoint(cp)) {
                throw new IllegalArgumentException(Integer.toString(cp));
            }
            n += Character.charCount(cp);
        }
        value = new char[n];
        int pos = 0;
        for (int i = offset; i < offset + count; i++) {
            int cp = codePoints[i];
            if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                value[pos++] = (char) cp;
            } else {
                value[pos++] = Character.highSurrogate(cp);
                value[pos++] = Character.lowSurrogate(cp);
            }
        }
    }

    public String(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    public String(byte[] bytes, int offset, int length) {
        checkRange(offset, length, bytes.length);
        value = StringCoding.decodeUtf8(bytes, offset, length);
    }

    public String(byte[] bytes, String charsetName) throws UnsupportedEncodingException {
        value = StringCoding.decode(bytes, 0, bytes.length, charsetName);
    }

    public String(byte[] bytes, int offset, int length, String charsetName)
            throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        checkRange(offset, length, bytes.length);
        value = StringCoding.decode(bytes, offset, length, charsetName);
    }

    public String(StringBuilder builder) {
        value = new char[builder.length()];
        builder.getChars(0, value.length, value, 0);
    }

    public String(StringBuffer buffer) {
        value = new char[buffer.length()];
        buffer.getChars(0, value.length, value, 0);
    }

    /** Wraps `chars` without copying; the caller must not keep or change the array. */
    String(char[] chars, boolean share) {
        value = chars;
    }

    private static void checkRange(int offset, int count, int length) {
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (count < 0) {
            throw new StringIndexOutOfBoundsException(count);
        }
        if (offset > length - count) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
    }

    public int length() {
        return value.length;
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    public char charAt(int index) {
        if (index < 0 || index >= value.length) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return value[index];
    }

    public int codePointAt(int index) {
        if (index < 0 || index >= value.length) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return Character.codePointAt(value, index, value.length);
    }

    public int codePointBefore(int index) {
        if (index < 1 || index > value.length) {
            throw new StringIndexOutOfBoundsException(index);
        }
        char low = value[index - 1];
        if (Character.isLowSurrogate(low) && index >= 2 && Character.isHighSurrogate(value[index - 2])) {
            return Character.toCodePoint(value[index - 2], low);
        }
        return low;
    }

    public int codePointCount(int beginIndex, int endIndex) {
        if (beginIndex < 0 || beginIndex > endIndex || endIndex > value.length) {
            throw new IndexOutOfBoundsException();
        }
        return StringSearch.codePointCount(value, beginIndex, endIndex);
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        if (index < 0 || index > value.length) {
            throw new IndexOutOfBoundsException();
        }
        return StringSearch.offsetByCodePoints(value, index, codePointOffset);
    }

    public void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        if (srcBegin < 0) {
            throw new StringIndexOutOfBoundsException(srcBegin);
        }
        if (srcEnd > value.length) {
            throw new StringIndexOutOfBoundsException(srcEnd);
        }
        if (srcBegin > srcEnd) {
            throw new StringIndexOutOfBoundsException(srcEnd - srcBegin);
        }
        System.arraycopy(value, srcBegin, dst, dstBegin, srcEnd - srcBegin);
    }

    public byte[] getBytes() {
        return StringCoding.encodeUtf8(value, 0, value.length);
    }

    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        return StringCoding.encode(value, 0, value.length, charsetName);
    }

    public boolean equals(Object anObject) {
        if (this == anObject) {
            return true;
        }
        if (!(anObject instanceof String)) {
            return false;
        }
        char[] other = ((String) anObject).value;
        if (other.length != value.length) {
            return false;
        }
        for (int i = 0; i < value.length; i++) {
            if (value[i] != other[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean contentEquals(CharSequence cs) {
        if (cs.length() != value.length) {
            return false;
        }
        for (int i = 0; i < value.length; i++) {
            if (cs.charAt(i) != value[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean contentEquals(StringBuffer sb) {
        return contentEquals((CharSequence) sb);
    }

    public boolean equalsIgnoreCase(String other) {
        return other != null && other.value.length == value.length
                && regionMatches(true, 0, other, 0, value.length);
    }

    public int compareTo(String other) {
        int n = Math.min(value.length, other.value.length);
        for (int i = 0; i < n; i++) {
            if (value[i] != other.value[i]) {
                return value[i] - other.value[i];
            }
        }
        return value.length - other.value.length;
    }

    public int compareToIgnoreCase(String other) {
        return StringSearch.compareIgnoreCase(value, other.value);
    }

    public boolean regionMatches(int toffset, String other, int ooffset, int len) {
        return regionMatches(false, toffset, other, ooffset, len);
    }

    public boolean regionMatches(boolean ignoreCase, int toffset, String other, int ooffset,
            int len) {
        return StringSearch.regionMatches(value, ignoreCase, toffset, other.value, ooffset, len);
    }

    public boolean startsWith(String prefix, int toffset) {
        return StringSearch.matchesAt(value, prefix.value, toffset);
    }

    public boolean startsWith(String prefix) {
        return startsWith(prefix, 0);
    }

    public boolean endsWith(String suffix) {
        return startsWith(suffix, value.length - suffix.value.length);
    }

    public int hashCode() {
        int h = hash;
        if (h == 0) {
            for (int i = 0; i < value.length; i++) {
                h = 31 * h + value[i];
            }
            hash = h;
        }
        return h;
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        return StringSearch.indexOf(value, ch, fromIndex);
    }

    public int lastIndexOf(int ch) {
        return lastIndexOf(ch, value.length - 1);
    }

    public int lastIndexOf(int ch, int fromIndex) {
        return StringSearch.lastIndexOf(value, ch, fromIndex);
    }

    public int indexOf(String str) {
        return indexOf(str, 0);
    }

    public int indexOf(String str, int fromIndex) {
        return StringSearch.indexOf(value, str.value, fromIndex);
    }

    public int lastIndexOf(String str) {
        return lastIndexOf(str, value.length);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return StringSearch.lastIndexOf(value, str.value, fromIndex);
    }

    public String substring(int beginIndex) {
        return substring(beginIndex, value.length);
    }

    public String substring(int beginIndex, int endIndex) {
        if (beginIndex < 0) {
            throw new StringIndexOutOfBoundsException(beginIndex);
        }
        if (endIndex > value.length) {
            throw new StringIndexOutOfBoundsException(endIndex);
        }
        if (beginIndex > endIndex) {
            throw new StringIndexOutOfBoundsException(endIndex - beginIndex);
        }
        if (beginIndex == 0 && endIndex == value.length) {
            return this;
        }
        char[] part = new char[endIndex - beginIndex];
        System.arraycopy(value, beginIndex, part, 0, part.length);
        return new String(part, true);
    }

    public CharSequence subSequence(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    public String concat(String str) {
        if (str.value.length == 0) {
            return this;
        }
        char[] all = new char[value.length + str.value.length];
        System.arraycopy(value, 0, all, 0, value.length);
        System.arraycopy(str.value, 0, all, value.length, str.value.length);
        return new String(all, true);
    }

    public String replace(char oldChar, char newChar) {
        if (oldChar == newChar || indexOf(oldChar) < 0) {
            return this;
        }
        char[] copy = new char[value.length];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = value[i] == oldChar ? newChar : value[i];
        }
        return new String(copy, true);
    }

    public String replace(CharSequence target, CharSequence replacement) {
        String t = target.toString();
        String r = replacement.toString();
        StringBuilder sb = new StringBuilder();
        if (t.value.length == 0) {
            for (int i = 0; i < value.length; i++) {
                sb.append(r).append(value[i]);
            }
            return sb.append(r).toString();
        }
        int from = 0;
        int at;
        while ((at = indexOf(t, from)) >= 0) {
            sb.append(value, from, at - from).append(r);
            from = at + t.value.length;
        }
        return sb.append(value, from, value.length - from).toString();
    }

    public boolean contains(CharSequence s) {
        return indexOf(s.toString()) >= 0;
    }

    public String toLowerCase() {
        return convertCase(false);
    }

    public String toUpperCase() {
        return convertCase(true);
    }

    private String convertCase(boolean upper) {
        return StringCase.convert(this, upper);
    }

    public String trim() {
        int st = 0;
        int len = value.length;
        while (st < len && value[st] <= ' ') {
            st++;
        }
        while (st < len && value[len - 1] <= ' ') {
            len--;
        }
        return (st > 0 || len < value.length) ? substring(st, len) : this;
    }

    public String toString() {
        return this;
    }

    public char[] toCharArray() {
        char[] copy = new char[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }

    public native String intern();

    public static String join(CharSequence delimiter, CharSequence... elements) {
        if (delimiter == null || elements == null) {
            throw new NullPointerException();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(elements[i]);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        if (delimiter == null || elements == null) {
            throw new NullPointerException();
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        Iterator<? extends CharSequence> it = elements.iterator();
        while (it.hasNext()) {
            if (!first) {
                sb.append(delimiter);
            }
            first = false;
            sb.append(it.next());
        }
        return sb.toString();
    }

    public static String valueOf(Object obj) {
        return obj == null ? "null" : obj.toString();
    }

    public static String valueOf(char[] data) {
        return new String(data);
    }

    public static String valueOf(char[] data, int offset, int count) {
        return new String(data, offset, count);
    }

    public static String copyValueOf(char[] data, int offset, int count) {
        return new String(data, offset, count);
    }

    public static String copyValueOf(char[] data) {
        return new String(data);
    }

    public static String valueOf(boolean b) {
        return b ? "true" : "false";
    }

    public static String valueOf(char c) {
        return new String(new char[] {c}, true);
    }

    public static String valueOf(int i) {
        return Integer.toString(i);
    }

    public static String valueOf(long l) {
        return Long.toString(l);
    }

    public static String valueOf(float f) {
        return Float.toString(f);
    }

    public static String valueOf(double d) {
        return Double.toString(d);
    }
}
