package lazy.internal;

import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationFormatError;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Builds annotation objects from the attribute bytes the VM hands out (JVMS 4.7.16 to 4.7.22).
 * Constants are read from the constant pool of `holder`, the class that owns the attribute.
 */
public final class AnnotationParser {
    private static final Annotation[] NONE = new Annotation[0];

    /** Guards the retention lookup, which parses annotations of annotation types. */
    private static int retentionDepth;

    private final byte[] bytes;
    private final Class<?> holder;
    private int pos;

    private AnnotationParser(byte[] bytes, Class<?> holder) {
        this.bytes = bytes;
        this.holder = holder;
    }

    // ---- entry points --------------------------------------------------

    /** The visible annotations in a RuntimeVisibleAnnotations body; those that cannot be loaded are dropped. */
    public static Annotation[] parse(byte[] raw, Class<?> holder) {
        if (raw == null) {
            return NONE;
        }
        AnnotationParser p = new AnnotationParser(raw, holder);
        return p.annotationList();
    }

    /** One annotation array per parameter; leading parameters the attribute does not cover get none. */
    public static Annotation[][] parseParameters(byte[] raw, Class<?> holder, int parameters) {
        Annotation[][] out = new Annotation[parameters][];
        int first = 0;
        AnnotationParser p = raw == null ? null : new AnnotationParser(raw, holder);
        int count = p == null ? 0 : p.u1();
        if (count < parameters) {
            first = parameters - count;
        }
        for (int i = 0; i < parameters; i++) {
            out[i] = NONE;
        }
        for (int i = 0; i < count; i++) {
            Annotation[] list = p.annotationList();
            if (first + i < parameters) {
                out[first + i] = list;
            }
        }
        return out;
    }

    /** The AnnotationDefault value of a method returning `returnType`, or null when it has none. */
    public static Object parseDefault(byte[] raw, Class<?> holder, Class<?> returnType) {
        return raw == null ? null : new AnnotationParser(raw, holder).value(returnType);
    }

    // ---- bytes ---------------------------------------------------------

    private int u1() {
        if (pos >= bytes.length) {
            throw new AnnotationFormatError("Unexpected end of annotation data");
        }
        return bytes[pos++] & 0xff;
    }

    private int u2() {
        int hi = u1();
        return (hi << 8) | u1();
    }

    private String utf8() {
        return VM.poolUtf8(holder, u2());
    }

    private Annotation[] annotationList() {
        int n = u2();
        Buf out = new Buf();
        for (int i = 0; i < n; i++) {
            Annotation a = annotation();
            if (a != null) {
                out.add(a);
            }
        }
        return (Annotation[]) out.toArray(Annotation.class);
    }

    // ---- annotations ---------------------------------------------------

    /** Reads one annotation structure; null when its type is missing or not runtime-visible. */
    private Annotation annotation() {
        String descriptor = utf8();
        int pairs = u2();
        Class<?> type = visibleType(descriptor);
        if (type == null) {
            for (int i = 0; i < pairs; i++) {
                u2();
                skipValue();
            }
            return null;
        }
        Method[] members = elements(type);
        Object[] values = new Object[members.length];
        Buf explicitOrder = new Buf();
        for (int i = 0; i < pairs; i++) {
            String name = utf8();
            int at = indexOf(members, name);
            if (at < 0) {
                skipValue();
            } else {
                values[at] = value(members[at].getReturnType());
                explicitOrder.add(Integer.valueOf(at));
            }
        }
        return create(type, members, values, explicitOrder);
    }

    /** The elements of an annotation type: its abstract instance methods, in class-file order. */
    static Method[] elements(Class<?> type) {
        Method[] all = type.getDeclaredMethods();
        Buf out = new Buf();
        for (int i = 0; i < all.length; i++) {
            int mods = all[i].getModifiers();
            if ((mods & java.lang.reflect.Modifier.ABSTRACT) != 0 && (mods & java.lang.reflect.Modifier.STATIC) == 0) {
                out.add(all[i]);
            }
        }
        return (Method[]) out.toArray(Method.class);
    }

    private static int indexOf(Method[] members, String name) {
        for (int i = 0; i < members.length; i++) {
            if (members[i].getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The annotation type if it loads, is an annotation and has RUNTIME retention. */
    private Class<?> visibleType(String descriptor) {
        Class<?> type;
        try {
            type = classOf(descriptor);
        } catch (TypeNotPresentException e) {
            return null;
        }
        return type.isAnnotation() && isRuntime(type) ? type : null;
    }

    private static boolean isRuntime(Class<?> type) {
        if (VM.isBootClass(type) || retentionDepth > 4) {
            return true;
        }
        retentionDepth++;
        try {
            Retention r = type.getAnnotation(Retention.class);
            return r != null && r.value() == RetentionPolicy.RUNTIME;
        } finally {
            retentionDepth--;
        }
    }

    /** Fills defaults, fixes the toString order the way a hash map of defaults would list it, and instantiates. */
    private Annotation create(Class<?> type, Method[] members, Object[] values, Buf explicitOrder) {
        String[] names = new String[members.length];
        boolean[] hasDefault = new boolean[members.length];
        for (int i = 0; i < members.length; i++) {
            names[i] = members[i].getName();
            Object d = members[i].getDefaultValue();
            hasDefault[i] = d != null;
            if (d != null && values[i] == null) {
                values[i] = d;
            }
        }
        // Colliding hash buckets list in an order the reference JVM derives from symbol addresses;
        // experiments show no rule, so ties keep declaration order.
        Buf defaultNames = new Buf();
        Buf defaultIndexes = new Buf();
        for (int i = 0; i < members.length; i++) {
            if (hasDefault[i]) {
                defaultNames.add(names[i]);
                defaultIndexes.add(Integer.valueOf(i));
            }
        }
        int[] order = printOrder(hasDefault, defaultNames, defaultIndexes, explicitOrder);
        try {
            Constructor<?> c = VM.annotationClass(type).getDeclaredConstructors()[0];
            AnnotationBase a = (AnnotationBase) c.newInstance(type, names, values);
            a.printOrder = order;
            return a;
        } catch (ReflectiveOperationException e) {
            throw new AnnotationFormatError(e);
        }
    }

    private static int[] printOrder(boolean[] hasDefault, Buf defaultNames, Buf defaultIndexes, Buf explicitOrder) {
        int[] order = new int[hasDefault.length];
        int k = 0;
        int[] hashed = HashOrder.order((String[]) defaultNames.toArray(String.class));
        for (int i = 0; i < hashed.length; i++) {
            order[k++] = ((Integer) defaultIndexes.get(hashed[i])).intValue();
        }
        for (int i = 0; i < explicitOrder.size(); i++) {
            int at = ((Integer) explicitOrder.get(i)).intValue();
            if (!hasDefault[at]) {
                order[k++] = at;
            }
        }
        // A member given neither a value nor a default stays listed, at the end.
        for (int i = 0; i < hasDefault.length; i++) {
            if (k < order.length && !hasDefault[i] && !contains(order, k, i)) {
                order[k++] = i;
            }
        }
        return order;
    }

    private static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) {
                return true;
            }
        }
        return false;
    }

    // ---- element values ------------------------------------------------

    /** Reads an element_value; `expected` is the member type, used for the component type of arrays. */
    private Object value(Class<?> expected) {
        int tag = u1();
        switch (tag) {
            case 'B': return Byte.valueOf((byte) VM.poolInt(holder, u2()));
            case 'C': return Character.valueOf((char) VM.poolInt(holder, u2()));
            case 'I': return Integer.valueOf(VM.poolInt(holder, u2()));
            case 'S': return Short.valueOf((short) VM.poolInt(holder, u2()));
            case 'Z': return Boolean.valueOf(VM.poolInt(holder, u2()) != 0);
            case 'J': return Long.valueOf(VM.poolLong(holder, u2()));
            case 'F': return Float.valueOf(VM.poolFloat(holder, u2()));
            case 'D': return Double.valueOf(VM.poolDouble(holder, u2()));
            case 's': return utf8();
            case 'e': return enumConstant();
            case 'c': return missingOr(utf8());
            case '@': return annotation();
            case '[': return array(expected);
            default: throw new AnnotationFormatError("Unknown element value tag " + tag);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumConstant() {
        String descriptor = utf8();
        String constant = utf8();
        Class type;
        try {
            type = classOf(descriptor);
        } catch (TypeNotPresentException e) {
            return new MissingType(e);
        }
        return Enum.valueOf(type, constant);
    }

    /** A class value, or a placeholder that throws when the element is read, as the reference JVM does. */
    private Object missingOr(String descriptor) {
        try {
            return classOf(descriptor);
        } catch (TypeNotPresentException e) {
            return new MissingType(e);
        }
    }

    private Object array(Class<?> expected) {
        int n = u2();
        Class<?> component = expected != null && expected.isArray() ? expected.getComponentType() : Object.class;
        Object out = Array.newInstance(component, n);
        for (int i = 0; i < n; i++) {
            Object v = value(component);
            if (v instanceof MissingType) {
                for (int rest = i + 1; rest < n; rest++) {
                    skipValue();
                }
                return v;
            }
            Array.set(out, i, v);
        }
        return out;
    }

    private void skipValue() {
        int tag = u1();
        switch (tag) {
            case 'e':
                u2();
                u2();
                break;
            case '@':
                u2();
                int pairs = u2();
                for (int i = 0; i < pairs; i++) {
                    u2();
                    skipValue();
                }
                break;
            case '[':
                int n = u2();
                for (int i = 0; i < n; i++) {
                    skipValue();
                }
                break;
            default:
                u2();
                break;
        }
    }

    // ---- classes -------------------------------------------------------

    /** The class a field descriptor names; "V" is void. */
    private Class<?> classOf(String d) {
        switch (d.charAt(0)) {
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'D': return double.class;
            case 'F': return float.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'S': return short.class;
            case 'Z': return boolean.class;
            case 'V': return void.class;
            case '[': return load(d.replace('/', '.'));
            default: return load(d.substring(1, d.length() - 1).replace('/', '.'));
        }
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, holder.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new TypeNotPresentException(name, e);
        }
    }
}
