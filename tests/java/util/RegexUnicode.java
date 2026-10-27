package util;

import java.util.regex.Pattern;

/** Unicode-aware classes on non-Latin and supplementary input: categories, blocks, scripts. */
public class RegexUnicode {
    /** The code points in [lo, hi] that the pattern matches, as hex ranges. */
    static void set(String re, int flags, int lo, int hi) {
        Pattern p = Pattern.compile(re, flags);
        StringBuilder sb = new StringBuilder(re + " f" + flags + " " + Integer.toHexString(lo) + ".." + Integer.toHexString(hi) + " =>");
        int start = -1;
        for (int c = lo; c <= hi + 1; c++) {
            boolean in = c <= hi && p.matcher(new String(Character.toChars(c))).matches();
            if (in && start < 0) {
                start = c;
            } else if (!in && start >= 0) {
                sb.append(' ').append(Integer.toHexString(start));
                if (c - 1 > start) {
                    sb.append('-').append(Integer.toHexString(c - 1));
                }
                start = -1;
            }
        }
        System.out.println(sb);
    }

    static void test(String re, int flags, String... inputs) {
        Pattern p = Pattern.compile(re, flags);
        StringBuilder sb = new StringBuilder(re + " f" + flags + " :");
        for (String s : inputs) {
            sb.append(' ').append(p.matcher(s).matches());
        }
        System.out.println(sb);
    }

    static String cp(int c) {
        return new String(Character.toChars(c));
    }

    public static void main(String[] args) {
        int u = Pattern.UNICODE_CHARACTER_CLASS;
        set("\\p{L}", 0, 0x370, 0x3ff);
        set("\\p{Lu}", 0, 0x370, 0x3ff);
        set("\\p{Ll}", 0, 0x370, 0x3ff);
        set("\\p{L}", 0, 0x400, 0x4ff);
        set("\\p{Nd}", 0, 0x600, 0x6ff);
        set("\\p{Nd}", 0, 0x900, 0x9ff);
        set("\\p{N}", 0, 0x2000, 0x2200);
        set("\\p{P}", 0, 0x2000, 0x2080);
        set("\\p{Sc}", 0, 0x20a0, 0x20d0);
        set("\\p{Sm}", 0, 0x2190, 0x2300);
        set("\\p{Mn}", 0, 0x300, 0x380);
        set("\\p{Zs}", 0, 0x2000, 0x2010);
        set("\\p{Cf}", 0, 0x200b, 0x2070);
        set("\\p{Lo}", 0, 0x4e00, 0x4e20);
        set("\\p{Lu}", 0, 0x1d400, 0x1d41f);
        set("\\p{L}", 0, 0x10400, 0x1044f);
        set("\\p{Cs}", 0, 0xd7ff, 0xe001);
        set("\\p{Co}", 0, 0xdfff, 0xe002);
        set("\\p{Cn}", 0, 0x378, 0x380);
        set("\\d", u, 0x660, 0x66a);
        set("\\d", 0, 0x660, 0x66a);
        set("\\d", u, 0x960, 0x970);
        set("\\w", u, 0x900, 0x910);
        set("\\w", u, 0x2000, 0x2080);
        set("\\s", u, 0x2000, 0x2030);
        set("\\p{Alpha}", u, 0x390, 0x3b0);
        set("\\p{Punct}", u, 0x2010, 0x2030);
        set("\\p{javaMirrored}", 0, 0x2000, 0x2300);
        set("\\p{javaTitleCase}", 0, 0x1c0, 0x1d0);
        set("\\p{IsIdeographic}", 0, 0x3000, 0x3010);
        set("\\p{javaDefined}", 0, 0x370, 0x380);
        set("\\p{InDevanagari}", 0, 0x8ff, 0x980);
        set("\\p{IsDevanagari}", 0, 0x8ff, 0x980);
        set("\\p{InGreek}", 0, 0x36f, 0x400);
        set("\\p{IsGreek}", 0, 0x36f, 0x400);
        set("\\p{IsCyrillic}", 0, 0x3ff, 0x530);
        set("\\p{InCyrillic}", 0, 0x3ff, 0x530);
        set("\\p{InHiragana}", 0, 0x303f, 0x30a1);
        set("\\p{IsHiragana}", 0, 0x303f, 0x30a1);
        set("\\p{IsKatakana}", 0, 0x30a0, 0x3100);
        set("\\p{IsThai}", 0, 0xe00, 0xe60);
        set("\\p{IsHebrew}", 0, 0x58f, 0x600);
        set("\\p{InGeneralPunctuation}", 0, 0x1fff, 0x2070);
        set("\\p{InCurrencySymbols}", 0, 0x209f, 0x20d1);
        set("\\p{InArrows}", 0, 0x218f, 0x2200);
        set("\\p{InCJKUnifiedIdeographs}", 0, 0x4dff, 0x4e01);
        set("\\p{InCJKUnifiedIdeographs}", 0, 0x9fff, 0xa000);
        set("\\p{IsHan}", 0, 0x4dbe, 0x4e01);
        test("\\p{IsHan}+", 0, cp(0x4e2d) + cp(0x6587), cp(0x20000), "a");
        test("\\p{IsHan}", 0, cp(0x20000), cp(0x2fa1d));
        test("\\p{IsLatin}+", 0, "abcé", "α");
        test("\\p{IsLatin}+", 0, "abcāḀ");
        test("\\p{IsGreek}+", 0, "αβΑ", "ἀ");
        test("\\p{Lu}", 0, cp(0x1d400), cp(0x1d41a));
        test("\\p{L}+", 0, cp(0x10400) + "a", cp(0x1f600));
        test("\\w+", u, "αбक" + cp(0x10400), "०x");
        test("\\w+", 0, "α");
        test("\\d+", u, "०१", "12२");
        test("[\\p{L}&&[^\\p{IsGreek}]]+", 0, "abcб", "α");
        test("\\p{L}\\p{M}*", 0, "é", "é̂", "́");
        test("(?i)бЖ", 0, "Бж");
        test("(?iu)бЖ", 0, "Бж");
        test("(?iu)[α-ω]+", 0, "ΑΩσς");
        test(".", 0, cp(0x10400), cp(0x1f600), "ab");
        test("\\x{10400}", 0, cp(0x10400));
        test("\\ud801\\udc00", 0, cp(0x10400));
        test("[\\x{10400}-\\x{1044f}]+", 0, cp(0x10400) + cp(0x1044f), cp(0x10450));
        String[] names = {"InLatin-1Supplement", "InLatin-1 Supplement", "InLatin1Supplement", "InLatin Extended-A", "InLatin Extended A", "InLatinExtended-A",
            "InLATIN_EXTENDED_A", "InGreek and Coptic", "InGreekandCoptic", "InCyrillic Supplement", "InCyrillicSupplementary", "InBasic_Latin", "Inbasiclatin",
            "InNoSuchBlock", "IsLatn", "IsLATIN", "IsOld_Italic", "IsOldItalic", "IsCommon", "IsZyyy", "IsInherited", "IsHani", "IsNoSuchScript", "sc=Grek", "script=greek",
            "blk=Arrows", "block=arrows", "gc=Lu", "gc=lu", "IsLu", "IsAlphabetic", "IsWhite_Space", "javaMirrored", "Is",
            "InSurrogatesArea", "InSurrogates_Area", "InSurrogates Area", "IsUnknown", "sc=Unknown", "script=UNKNOWN", "IsZzzz", "InLinear-B Syllabary", "InLinear B Syllabary",
            "InLinear_B_Syllabary", "InPhags-pa", "InPhags pa", "InPhags_Pa", "InCJK Unified Ideographs Extension A", "InCJK_Unified_Ideographs_Extension_A",
            "InCJKUnifiedIdeographsExtensionA", "InCJK Unified Ideographs Extension-A", "IsLinear-B", "IsLinear_B", "IsLinear B", "IsOld Italic", "InPrivate Use", "InPrivateUse",
            "InCombining Diacritical Marks for Symbols", "InCombiningMarksforSymbols", "InCombining Marks for Symbols"};
        for (String n : names) {
            try {
                Pattern.compile("\\p{" + n + "}");
                System.out.println(n + " : ok");
            } catch (java.util.regex.PatternSyntaxException e) {
                System.out.println(n + " : " + e.getDescription());
            }
        }
        set("\\p{IsCommon}", 0, 0x20, 0x7f);
        set("\\p{IsInherited}", 0, 0x300, 0x320);
        set("\\p{InMiscellaneousSymbols}", 0, 0x25ff, 0x2700);
        set("\\p{IsUnknown}", 0, 0x377, 0x380);
        set("\\p{InSurrogates_Area}", 0, 0xd7ff, 0xe000);
        set("\\p{InHighSurrogates}", 0, 0xd7ff, 0xdc00);
        System.out.println("done");
    }
}
