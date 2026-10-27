package java.util;

public final class StringJoiner {
    private final String prefix;
    private final String delimiter;
    private final String suffix;
    /** Prefix plus the elements so far; null until the first add. */
    private StringBuilder value;
    private String emptyValue;

    public StringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public StringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        if (prefix == null) {
            throw new NullPointerException("The prefix must not be null");
        }
        if (delimiter == null) {
            throw new NullPointerException("The delimiter must not be null");
        }
        if (suffix == null) {
            throw new NullPointerException("The suffix must not be null");
        }
        this.prefix = prefix.toString();
        this.delimiter = delimiter.toString();
        this.suffix = suffix.toString();
    }

    public StringJoiner setEmptyValue(CharSequence emptyValue) {
        if (emptyValue == null) {
            throw new NullPointerException("The empty value must not be null");
        }
        this.emptyValue = emptyValue.toString();
        return this;
    }

    public String toString() {
        if (value == null) {
            return emptyValue != null ? emptyValue : prefix + suffix;
        }
        return value.toString() + suffix;
    }

    public StringJoiner add(CharSequence newElement) {
        start().append(String.valueOf(newElement));
        return this;
    }

    public StringJoiner merge(StringJoiner other) {
        if (other == null) {
            throw new NullPointerException();
        }
        if (other.value != null) {
            // Copy first: `other` may be this joiner.
            String content = other.value.substring(other.prefix.length());
            start().append(content);
        }
        return this;
    }

    public int length() {
        if (value == null) {
            return emptyValue != null ? emptyValue.length() : prefix.length() + suffix.length();
        }
        return value.length() + suffix.length();
    }

    private StringBuilder start() {
        if (value == null) {
            value = new StringBuilder().append(prefix);
        } else {
            value.append(delimiter);
        }
        return value;
    }
}
