package java.util;

/**
 * A language, script, country, variant and extensions. LazyVM has little
 * locale data: Formatter and Scanner know the number symbols of a few
 * languages (LocaleSymbols); everything else behaves as en-US. The default
 * comes from the user.language, user.country, user.variant and user.script
 * properties, which LazyVM sets to en, US, "" and "" unless -D says otherwise.
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

    /** The two kinds of default locale a program can set apart. */
    public enum Category {
        DISPLAY, FORMAT
    }

    private static Locale defaultLocale;
    private static Locale formatLocale;
    private static Locale displayLocale;

    private final String language;
    private final String script;
    private final String country;
    private final String variant;
    /** Canonical extension text such as "a-bb-u-ca-gregory-x-foo"; "" when there are none. */
    private final String extensions;

    public Locale(String language, String country, String variant) {
        this(nonNull(language), "", nonNull(country), nonNull(variant), "");
    }

    public Locale(String language, String country) {
        this(language, country, "");
    }

    public Locale(String language) {
        this(language, "", "");
    }

    private static String nonNull(String s) {
        if (s == null) {
            throw new NullPointerException();
        }
        return s;
    }

    /** Callers have checked for null; a non-empty `extensions` is already canonical. */
    Locale(String language, String script, String country, String variant, String extensions) {
        this.language = oldCode(ascii(language, false));
        this.script = script;
        this.country = ascii(country, true);
        this.variant = variant;
        this.extensions = extensions.isEmpty() ? legacyExtension() : extensions;
    }

    // Japanese and Thai calendars and digits were once spelled as a variant (ja_JP_JP, th_TH_TH).
    private String legacyExtension() {
        if (language.equals("ja") && country.equals("JP") && variant.equals("JP")) {
            return "u-ca-japanese";
        }
        if (language.equals("th") && country.equals("TH") && variant.equals("TH")) {
            return "u-nu-thai";
        }
        return "";
    }

    // Java 8 keeps the pre-1989 codes for Hebrew, Yiddish and Indonesian.
    private static String oldCode(String lang) {
        if (lang.equals("he")) {
            return "iw";
        }
        if (lang.equals("yi")) {
            return "ji";
        }
        return lang.equals("id") ? "in" : lang;
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

    public static Locale forLanguageTag(String languageTag) {
        return LocaleTags.parse(languageTag);
    }

    public String toLanguageTag() {
        return LocaleTags.format(language, script, country, variant, extensions);
    }

    private static String property(String key, String fallbackKey) {
        String v = System.getProperty(key);
        return v != null ? v : System.getProperty(fallbackKey);
    }

    // `suffix` is "" for the base default, ".format" or ".display" for a category.
    private static Locale fromProperties(String suffix) {
        String lang = property("user.language" + suffix, "user.language");
        String script = property("user.script" + suffix, "user.script");
        String region = property("user.country" + suffix, "user.country");
        String variant = property("user.variant" + suffix, "user.variant");
        return new Locale(lang == null || lang.isEmpty() ? "en" : lang, script == null ? "" : script,
                region == null ? "" : region, variant == null ? "" : variant, "");
    }

    public static synchronized Locale getDefault() {
        if (defaultLocale == null) {
            defaultLocale = fromProperties("");
        }
        return defaultLocale;
    }

    public static synchronized Locale getDefault(Category category) {
        if (category == Category.FORMAT) {
            if (formatLocale == null) {
                formatLocale = fromProperties(".format");
            }
            return formatLocale;
        }
        if (category == null) {
            throw new NullPointerException();
        }
        if (displayLocale == null) {
            displayLocale = fromProperties(".display");
        }
        return displayLocale;
    }

    /** Sets the default and, as the specification says, both category defaults. */
    public static synchronized void setDefault(Locale newLocale) {
        if (newLocale == null) {
            throw new NullPointerException("Can't set default locale to NULL");
        }
        defaultLocale = newLocale;
        formatLocale = newLocale;
        displayLocale = newLocale;
    }

    public static synchronized void setDefault(Category category, Locale newLocale) {
        if (category == null) {
            throw new NullPointerException("Category cannot be NULL");
        }
        if (newLocale == null) {
            throw new NullPointerException("Can't set default locale to NULL");
        }
        if (category == Category.FORMAT) {
            formatLocale = newLocale;
        } else {
            displayLocale = newLocale;
        }
    }

    public String getLanguage() {
        return language;
    }

    public String getScript() {
        return script;
    }

    public String getCountry() {
        return country;
    }

    public String getVariant() {
        return variant;
    }

    /** The extension for a singleton key, without the key, or null. */
    public String getExtension(char key) {
        if (!(key >= 'a' && key <= 'z' || key >= 'A' && key <= 'Z')) {
            throw new IllegalArgumentException("Ill-formed extension key: " + key);
        }
        return LocaleTags.extension(extensions, Character.toLowerCase(key));
    }

    public Set<Character> getExtensionKeys() {
        return LocaleTags.extensionKeys(extensions);
    }

    public Set<String> getUnicodeLocaleAttributes() {
        return LocaleTags.unicodeAttributes(extensions);
    }

    public String getUnicodeLocaleType(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
        if (!LocaleTags.isUnicodeKey(key)) {
            throw new IllegalArgumentException("Ill-formed Unicode locale key: " + key);
        }
        return LocaleTags.unicodeType(extensions, ascii(key, false));
    }

    public Set<String> getUnicodeLocaleKeys() {
        return LocaleTags.unicodeKeys(extensions);
    }

    /** language_COUNTRY_variant, then "_#" and the script and extensions when there are any. */
    public final String toString() {
        if (language.isEmpty() && country.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(language);
        boolean tail = !script.isEmpty() || !extensions.isEmpty();
        if (!country.isEmpty() || !variant.isEmpty() || tail) {
            sb.append('_').append(country);
        }
        if (!variant.isEmpty()) {
            sb.append('_').append(variant);
        }
        if (tail) {
            sb.append("_#").append(script);
            if (!extensions.isEmpty()) {
                sb.append(script.isEmpty() ? "" : "_").append(extensions);
            }
        }
        return sb.toString();
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Locale)) {
            return false;
        }
        Locale other = (Locale) obj;
        return language.equals(other.language) && script.equals(other.script)
                && country.equals(other.country) && variant.equals(other.variant)
                && extensions.equals(other.extensions);
    }

    // Matches the oracle's values, found by probing: the four parts fold 31-wise and the
    // extension text is mixed in by xor.
    public int hashCode() {
        int h = ((language.hashCode() * 31 + script.hashCode()) * 31 + country.hashCode()) * 31
                + variant.hashCode();
        return extensions.isEmpty() ? h : h ^ extensions.hashCode();
    }
}
