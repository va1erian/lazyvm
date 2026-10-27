package java.util.regex;

/** Resolves the name in \p{name} / \P{name} to a character class. */
final class Props {
    private Props() {
    }

    /**
     * The class for `name`, or null when it names nothing known. `failure`
     * (length 1) receives the description to report for an unknown name.
     */
    static CharClass resolve(String name, boolean unicodeClasses, String[] failure) {
        int eq = name.indexOf('=');
        if (eq >= 0) {
            return keyValue(name.substring(0, eq), name.substring(eq + 1), failure);
        }
        CharClass result;
        if (name.startsWith("In")) {
            result = Blocks.block(name.substring(2));
            failure[0] = "Unknown character block name {" + name.substring(2) + "}";
        } else if (name.startsWith("Is")) {
            String rest = name.substring(2);
            result = category(rest);
            if (result == null) {
                result = binary(rest);
            }
            if (result == null) {
                result = javaClass(rest);
            }
            if (result == null) {
                result = isPosix(rest);
            }
            if (result == null) {
                result = Blocks.script(rest);
            }
            failure[0] = "Unknown character script name {" + rest + "}";
        } else {
            result = plain(unicodeClasses ? canonicalPosix(name) : name, unicodeClasses);
            failure[0] = "Unknown character property name {" + name + "}";
        }
        return result;
    }

    /** A POSIX, java* or category name, as written alone or as the value of gc=. */
    private static CharClass plain(String name, boolean unicodeClasses) {
        CharClass result = name.startsWith("java") ? javaClass(name) : posix(name, unicodeClasses);
        return result != null ? result : category(name);
    }

    /**
     * Observed on the oracle (RegexNames): with UNICODE_CHARACTER_CLASS a bare POSIX name is
     * matched ignoring case (\p{alpha}), except ASCII; the value of gc= and the Is forms never are.
     */
    private static String canonicalPosix(String name) {
        String[] posix = {"Lower", "Upper", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space"};
        for (String p : posix) {
            if (p.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return name;
    }

    private static CharClass keyValue(String rawKey, String value, String[] failure) {
        String key = rawKey.toLowerCase();
        if (key.equals("sc") || key.equals("script")) {
            failure[0] = "Unknown character script name {" + value + "}";
            return Blocks.script(value);
        }
        if (key.equals("blk") || key.equals("block")) {
            failure[0] = "Unknown character block name {" + value + "}";
            return Blocks.block(value);
        }
        if (key.equals("gc") || key.equals("general_category")) {
            failure[0] = "Unknown character property name {" + value + "}";
            // Observed on the oracle (RegexNames): a POSIX name here stays ASCII under UNICODE_CHARACTER_CLASS.
            return plain(value, false);
        }
        failure[0] = "Unknown Unicode property {name=<" + key + ">, value=<" + value + ">}";
        return null;
    }

    private static CharClass category(String name) {
        int mask = Category.mask(name);
        if (mask == 0) {
            return null;
        }
        CharClass cc = CharClass.categories(mask);
        return mask == -1 ? cc : cc.markCategoryOrBlock();
    }

    private static CharClass binary(String name) {
        String n = name.toUpperCase();
        int kind;
        if (n.equals("ALPHABETIC")) {
            kind = CharClass.U_ALPHA;
        } else if (n.equals("IDEOGRAPHIC")) {
            kind = CharClass.U_IDEOGRAPHIC;
        } else if (n.equals("LETTER")) {
            kind = CharClass.J_LETTER;
        } else if (n.equals("LOWERCASE")) {
            kind = CharClass.U_LOWER;
        } else if (n.equals("UPPERCASE")) {
            kind = CharClass.U_UPPER;
        } else if (n.equals("TITLECASE")) {
            kind = CharClass.J_TITLE;
        } else if (n.equals("PUNCTUATION")) {
            kind = CharClass.U_PUNCT;
        } else if (n.equals("CONTROL")) {
            kind = CharClass.U_CNTRL;
        } else if (n.equals("WHITE_SPACE") || n.equals("WHITESPACE")) {
            kind = CharClass.U_SPACE;
        } else if (n.equals("DIGIT")) {
            kind = CharClass.U_DIGIT;
        } else if (n.equals("HEX_DIGIT") || n.equals("HEXDIGIT")) {
            kind = CharClass.U_XDIGIT;
        } else if (n.equals("JOIN_CONTROL") || n.equals("JOINCONTROL")) {
            kind = CharClass.U_JOIN;
        } else if (n.equals("NONCHARACTER_CODE_POINT") || n.equals("NONCHARACTERCODEPOINT")) {
            kind = CharClass.U_NONCHAR;
        } else if (n.equals("ASSIGNED")) {
            kind = CharClass.U_ASSIGNED;
        } else if (n.equals("WORD")) {
            kind = CharClass.U_WORD;
        } else {
            return null;
        }
        return CharClass.of(kind);
    }

    private static CharClass javaClass(String name) {
        int kind;
        if (name.equals("javaLowerCase")) {
            kind = CharClass.U_LOWER;
        } else if (name.equals("javaUpperCase")) {
            kind = CharClass.U_UPPER;
        } else if (name.equals("javaWhitespace")) {
            kind = CharClass.J_WHITESPACE;
        } else if (name.equals("javaMirrored")) {
            kind = CharClass.J_MIRRORED;
        } else if (name.equals("javaLetter")) {
            kind = CharClass.J_LETTER;
        } else if (name.equals("javaDigit")) {
            kind = CharClass.U_DIGIT;
        } else if (name.equals("javaLetterOrDigit")) {
            kind = CharClass.J_LETTER_OR_DIGIT;
        } else if (name.equals("javaAlphabetic")) {
            kind = CharClass.U_ALPHA;
        } else if (name.equals("javaIdeographic")) {
            kind = CharClass.U_IDEOGRAPHIC;
        } else if (name.equals("javaTitleCase")) {
            kind = CharClass.J_TITLE;
        } else if (name.equals("javaSpaceChar")) {
            kind = CharClass.J_SPACECHAR;
        } else if (name.equals("javaISOControl")) {
            kind = CharClass.J_ISOCONTROL;
        } else if (name.equals("javaDefined")) {
            kind = CharClass.J_DEFINED;
        } else if (name.equals("javaJavaIdentifierStart")) {
            kind = CharClass.J_JAVA_START;
        } else if (name.equals("javaJavaIdentifierPart")) {
            kind = CharClass.J_JAVA_PART;
        } else if (name.equals("javaUnicodeIdentifierStart")) {
            kind = CharClass.J_UNI_START;
        } else if (name.equals("javaUnicodeIdentifierPart")) {
            kind = CharClass.J_UNI_PART;
        } else if (name.equals("javaIdentifierIgnorable")) {
            kind = CharClass.J_IGNORABLE;
        } else {
            return null;
        }
        return CharClass.of(kind);
    }

    /**
     * The POSIX names spelled with an Is prefix. Observed on the oracle: they
     * ignore UNICODE_CHARACTER_CLASS; Alnum, Graph, Print and Blank are the
     * Unicode sets and every other name stays ASCII. Pinned by RegexNames,
     * blessed in RegexNames.out; killed by routing these through posix(name, flag).
     */
    private static CharClass isPosix(String name) {
        boolean wide = name.equals("Alnum") || name.equals("Graph") || name.equals("Print")
            || name.equals("Blank");
        return posix(name, wide);
    }

    /** The POSIX classes: ASCII only, or Unicode-aware under UNICODE_CHARACTER_CLASS. */
    static CharClass posix(String name, boolean unicode) {
        if (name.equals("ASCII")) {
            return CharClass.range(0, 0x7F);
        }
        if (unicode) {
            return unicodePosix(name);
        }
        CharClass lower = CharClass.range('a', 'z');
        CharClass upper = CharClass.range('A', 'Z');
        CharClass digit = CharClass.range('0', '9');
        CharClass punct = punctuation();
        CharClass alnum = CharClass.or(CharClass.or(lower, upper), digit);
        CharClass result;
        if (name.equals("Lower")) {
            result = lower;
        } else if (name.equals("Upper")) {
            result = upper;
        } else if (name.equals("Alpha")) {
            result = CharClass.or(lower, upper);
        } else if (name.equals("Digit")) {
            result = digit;
        } else if (name.equals("Alnum")) {
            result = alnum;
        } else if (name.equals("Punct")) {
            result = punct;
        } else if (name.equals("Graph")) {
            result = CharClass.or(alnum, punct);
        } else if (name.equals("Print")) {
            result = CharClass.or(CharClass.or(alnum, punct), CharClass.single(' '));
        } else if (name.equals("Blank")) {
            result = CharClass.or(CharClass.single(' '), CharClass.single('\t'));
        } else if (name.equals("Cntrl")) {
            result = CharClass.or(CharClass.range(0, 0x1F), CharClass.single(0x7F));
        } else if (name.equals("XDigit")) {
            result = CharClass.or(CharClass.or(digit, CharClass.range('a', 'f')), CharClass.range('A', 'F'));
        } else if (name.equals("Space")) {
            result = CharClass.of(CharClass.SPACE);
        } else {
            return null;
        }
        return CharClass.cached(result);
    }

    private static CharClass punctuation() {
        return CharClass.or(CharClass.or(CharClass.range('!', '/'), CharClass.range(':', '@')),
            CharClass.or(CharClass.range('[', '`'), CharClass.range('{', '~')));
    }

    private static CharClass unicodePosix(String name) {
        int kind;
        if (name.equals("Lower")) {
            kind = CharClass.U_LOWER;
        } else if (name.equals("Upper")) {
            kind = CharClass.U_UPPER;
        } else if (name.equals("Alpha")) {
            kind = CharClass.U_ALPHA;
        } else if (name.equals("Digit")) {
            kind = CharClass.U_DIGIT;
        } else if (name.equals("Alnum")) {
            kind = CharClass.U_ALNUM;
        } else if (name.equals("Punct")) {
            kind = CharClass.U_PUNCT;
        } else if (name.equals("Graph")) {
            kind = CharClass.U_GRAPH;
        } else if (name.equals("Print")) {
            kind = CharClass.U_PRINT;
        } else if (name.equals("Blank")) {
            kind = CharClass.U_BLANK;
        } else if (name.equals("Cntrl")) {
            kind = CharClass.U_CNTRL;
        } else if (name.equals("XDigit")) {
            kind = CharClass.U_XDIGIT;
        } else if (name.equals("Space")) {
            kind = CharClass.U_SPACE;
        } else {
            return null;
        }
        return CharClass.of(kind);
    }
}
