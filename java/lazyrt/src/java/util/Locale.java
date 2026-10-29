package java.util;

/**
 * A language/country/variant triple. LazyVM has no locale data: formatting
 * always uses the en-US conventions, whatever locale is passed.
 */
public final class Locale implements java.io.Serializable {
    public static final Locale ENGLISH = new Locale("en", "");
    public static final Locale FRENCH = new Locale("fr", "");
    public static final Locale GERMAN = new Locale("de", "");
    public static final Locale ITALIAN = new Locale("it", "");
    public static final Locale JAPANESE = new Locale("ja", "");
    public static final Locale KOREAN = new Locale("ko", "");
    public static final Locale CHINESE = new Locale("zh", "");
    public static final Locale SIMPLIFIED_CHINESE = new Locale("zh", "CN");
    public static final Locale TRADITIONAL_CHINESE = new Locale("zh", "TW");
    public static final Locale FRANCE = new Locale("fr", "FR");
    public static final Locale GERMANY = new Locale("de", "DE");
    public static final Locale ITALY = new Locale("it", "IT");
    public static final Locale JAPAN = new Locale("ja", "JP");
    public static final Locale KOREA = new Locale("ko", "KR");
    public static final Locale CHINA = SIMPLIFIED_CHINESE;
    public static final Locale PRC = SIMPLIFIED_CHINESE;
    public static final Locale TAIWAN = TRADITIONAL_CHINESE;
    public static final Locale UK = new Locale("en", "GB");
    public static final Locale US = new Locale("en", "US");
    public static final Locale CANADA = new Locale("en", "CA");
    public static final Locale CANADA_FRENCH = new Locale("fr", "CA");
    public static final Locale ROOT = new Locale("", "");

    private static Locale defaultLocale = US;

    private final String language;
    private final String country;
    private final String variant;

    public Locale(String language, String country, String variant) {
        if (language == null || country == null || variant == null) {
            throw new NullPointerException();
        }
        this.language = ascii(language, false);
        this.country = ascii(country, true);
        this.variant = variant;
    }

    // Only the ASCII letters change case, whatever the default locale is.
    private static String ascii(String s, boolean upper) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (upper ? c >= 'a' && c <= 'z' : c >= 'A' && c <= 'Z') {
                chars[i] = (char) (c ^ 0x20);
            }
        }
        return new String(chars);
    }

    public Locale(String language, String country) {
        this(language, country, "");
    }

    public Locale(String language) {
        this(language, "", "");
    }

    public static Locale getDefault() {
        return defaultLocale;
    }

    public static synchronized void setDefault(Locale newLocale) {
        if (newLocale == null) {
            throw new NullPointerException("Can't set default locale to NULL");
        }
        defaultLocale = newLocale;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }

    public String getVariant() {
        return variant;
    }

    /** language_COUNTRY_variant, with trailing empty parts dropped. */
    public final String toString() {
        if (language.isEmpty() && country.isEmpty()) {
            return "";
        }
        if (variant.isEmpty()) {
            return country.isEmpty() ? language : language + "_" + country;
        }
        return language + "_" + country + "_" + variant;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Locale)) {
            return false;
        }
        Locale other = (Locale) obj;
        return language.equals(other.language) && country.equals(other.country)
                && variant.equals(other.variant);
    }

    public int hashCode() {
        return (language.hashCode() * 31 + country.hashCode()) * 31 + variant.hashCode();
    }
}
