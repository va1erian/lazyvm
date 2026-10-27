package java.lang.reflect;

public class Modifier {
    public static final int PUBLIC = 0x00000001;
    public static final int PRIVATE = 0x00000002;
    public static final int PROTECTED = 0x00000004;
    public static final int STATIC = 0x00000008;
    public static final int FINAL = 0x00000010;
    public static final int SYNCHRONIZED = 0x00000020;
    public static final int VOLATILE = 0x00000040;
    public static final int TRANSIENT = 0x00000080;
    public static final int NATIVE = 0x00000100;
    public static final int INTERFACE = 0x00000200;
    public static final int ABSTRACT = 0x00000400;
    public static final int STRICT = 0x00000800;

    static final int BRIDGE = 0x00000040;
    static final int VARARGS = 0x00000080;
    static final int SYNTHETIC = 0x00001000;
    static final int ANNOTATION = 0x00002000;
    static final int ENUM = 0x00004000;
    static final int MANDATED = 0x00008000;

    private static final int CLASS_MODIFIERS =
            PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | FINAL | STRICT;
    private static final int INTERFACE_MODIFIERS =
            PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | STRICT;
    private static final int CONSTRUCTOR_MODIFIERS = PUBLIC | PROTECTED | PRIVATE;
    private static final int METHOD_MODIFIERS =
            PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | FINAL | SYNCHRONIZED | NATIVE | STRICT;
    private static final int FIELD_MODIFIERS =
            PUBLIC | PROTECTED | PRIVATE | STATIC | FINAL | TRANSIENT | VOLATILE;
    private static final int PARAMETER_MODIFIERS = FINAL;

    public Modifier() {
    }

    public static boolean isPublic(int mod) {
        return (mod & PUBLIC) != 0;
    }

    public static boolean isPrivate(int mod) {
        return (mod & PRIVATE) != 0;
    }

    public static boolean isProtected(int mod) {
        return (mod & PROTECTED) != 0;
    }

    public static boolean isStatic(int mod) {
        return (mod & STATIC) != 0;
    }

    public static boolean isFinal(int mod) {
        return (mod & FINAL) != 0;
    }

    public static boolean isSynchronized(int mod) {
        return (mod & SYNCHRONIZED) != 0;
    }

    public static boolean isVolatile(int mod) {
        return (mod & VOLATILE) != 0;
    }

    public static boolean isTransient(int mod) {
        return (mod & TRANSIENT) != 0;
    }

    public static boolean isNative(int mod) {
        return (mod & NATIVE) != 0;
    }

    public static boolean isInterface(int mod) {
        return (mod & INTERFACE) != 0;
    }

    public static boolean isAbstract(int mod) {
        return (mod & ABSTRACT) != 0;
    }

    public static boolean isStrict(int mod) {
        return (mod & STRICT) != 0;
    }

    /** The keywords of the set bits, in the fixed order the Javadoc gives, space separated. */
    public static String toString(int mod) {
        StringBuilder sb = new StringBuilder();
        add(sb, mod, PUBLIC, "public");
        add(sb, mod, PROTECTED, "protected");
        add(sb, mod, PRIVATE, "private");
        add(sb, mod, ABSTRACT, "abstract");
        add(sb, mod, STATIC, "static");
        add(sb, mod, FINAL, "final");
        add(sb, mod, TRANSIENT, "transient");
        add(sb, mod, VOLATILE, "volatile");
        add(sb, mod, SYNCHRONIZED, "synchronized");
        add(sb, mod, NATIVE, "native");
        add(sb, mod, STRICT, "strictfp");
        add(sb, mod, INTERFACE, "interface");
        return sb.toString();
    }

    private static void add(StringBuilder sb, int mod, int bit, String word) {
        if ((mod & bit) != 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word);
        }
    }

    public static int classModifiers() {
        return CLASS_MODIFIERS;
    }

    public static int interfaceModifiers() {
        return INTERFACE_MODIFIERS;
    }

    public static int constructorModifiers() {
        return CONSTRUCTOR_MODIFIERS;
    }

    public static int methodModifiers() {
        return METHOD_MODIFIERS;
    }

    public static int fieldModifiers() {
        return FIELD_MODIFIERS;
    }

    public static int parameterModifiers() {
        return PARAMETER_MODIFIERS;
    }
}
