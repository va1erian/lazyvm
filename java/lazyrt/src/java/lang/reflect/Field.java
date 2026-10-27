package java.lang.reflect;

import java.lang.annotation.Annotation;

public final class Field extends AccessibleObject implements Member {
    // Filled by the VM (docs/design/reflection.md); keep names and types.
    private Class<?> clazz;
    private String name;
    private Class<?> type;
    private int modifiers;
    private int slot;
    private String signature;
    private byte[] annotations;

    private Annotation[] annotationCache;

    private Field() {
    }

    private static native Object get0(Field f, Object obj);

    private static native void set0(Field f, Object obj, Object value);

    public Class<?> getDeclaringClass() {
        return clazz;
    }

    public String getName() {
        return name;
    }

    public int getModifiers() {
        return modifiers;
    }

    public boolean isEnumConstant() {
        return (modifiers & Modifier.ENUM) != 0;
    }

    public boolean isSynthetic() {
        return (modifiers & Modifier.SYNTHETIC) != 0;
    }

    public Class<?> getType() {
        return type;
    }

    public Type getGenericType() {
        if (signature == null) {
            return type;
        }
        return new lazy.internal.SignatureParser(signature, clazz, clazz).fieldType();
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof Field)) {
            return false;
        }
        Field other = (Field) obj;
        return clazz == other.clazz && name.equals(other.name) && type == other.type;
    }

    public int hashCode() {
        return clazz.getName().hashCode() ^ name.hashCode();
    }

    public String toString() {
        return modifierText() + type.getTypeName() + " " + clazz.getTypeName() + "." + name;
    }

    public String toGenericString() {
        return modifierText() + getGenericType().getTypeName() + " " + clazz.getTypeName() + "." + name;
    }

    private String modifierText() {
        String text = Modifier.toString(modifiers & Modifier.fieldModifiers());
        return text.length() == 0 ? "" : text + " ";
    }

    private String qualifiedName() {
        return clazz.getName() + "." + name;
    }

    /** "static final " style prefix the error messages of the Javadoc era put before the type. */
    private String shapePrefix() {
        return (Modifier.isStatic(modifiers) ? "static " : "") + (Modifier.isFinal(modifiers) ? "final " : "");
    }

    private IllegalArgumentException cannotSet(String what) {
        return new IllegalArgumentException(
                "Can not set " + shapePrefix() + type.getName() + " field " + qualifiedName() + " to " + what);
    }

    private static String describe(Object value) {
        return value == null ? "null value" : value.getClass().getName();
    }

    /** Checks the receiver of an access; static fields ignore it. */
    private void checkReceiver(Object obj) {
        if (Modifier.isStatic(modifiers)) {
            return;
        }
        if (obj == null) {
            throw new NullPointerException();
        }
        if (!clazz.isInstance(obj)) {
            throw cannotSet(obj.getClass().getName());
        }
    }

    public Object get(Object obj) throws IllegalArgumentException, IllegalAccessException {
        checkReceiver(obj);
        return get0(this, obj);
    }

    /** Reads the field and widens it to the primitive kind `to`, as the typed getters specify. */
    private Object read(Object obj, char to, String targetName) {
        checkReceiver(obj);
        Object v = Convert.primitive(get0(this, obj), to);
        if (v == Convert.FAIL || !type.isPrimitive()) {
            throw new IllegalArgumentException("Attempt to get " + type.getName() + " field \""
                    + qualifiedName() + "\" with illegal data type conversion to " + targetName);
        }
        return v;
    }

    public boolean getBoolean(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Boolean) read(obj, 'Z', "boolean")).booleanValue();
    }

    public byte getByte(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Byte) read(obj, 'B', "byte")).byteValue();
    }

    public char getChar(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Character) read(obj, 'C', "char")).charValue();
    }

    public short getShort(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Short) read(obj, 'S', "short")).shortValue();
    }

    public int getInt(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Integer) read(obj, 'I', "int")).intValue();
    }

    public long getLong(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Long) read(obj, 'J', "long")).longValue();
    }

    public float getFloat(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Float) read(obj, 'F', "float")).floatValue();
    }

    public double getDouble(Object obj) throws IllegalArgumentException, IllegalAccessException {
        return ((Double) read(obj, 'D', "double")).doubleValue();
    }

    /** Final fields are writable only when made accessible, and never when static. */
    private void checkWritable(String what) throws IllegalAccessException {
        if (Modifier.isFinal(modifiers) && (Modifier.isStatic(modifiers) || !override)) {
            throw new IllegalAccessException("Can not set " + shapePrefix() + type.getName() + " field "
                    + qualifiedName() + " to " + what);
        }
    }

    public void set(Object obj, Object value) throws IllegalArgumentException, IllegalAccessException {
        checkReceiver(obj);
        checkWritable(describe(value));
        Object v = Convert.to(value, type);
        if (v == Convert.FAIL) {
            throw cannotSet(describe(value));
        }
        set0(this, obj, v);
    }

    /** Writes a value given to a typed setter; the field must be primitive and accept it by widening. */
    private void write(Object obj, Object boxed, String what) throws IllegalAccessException {
        checkReceiver(obj);
        checkWritable(what);
        Object v = type.isPrimitive() ? Convert.to(boxed, type) : Convert.FAIL;
        if (v == Convert.FAIL) {
            throw cannotSet(what);
        }
        set0(this, obj, v);
    }

    public void setBoolean(Object obj, boolean z) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Boolean.valueOf(z), "(boolean)" + z);
    }

    public void setByte(Object obj, byte b) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Byte.valueOf(b), "(byte)" + b);
    }

    public void setChar(Object obj, char c) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Character.valueOf(c), "(char)" + c);
    }

    public void setShort(Object obj, short s) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Short.valueOf(s), "(short)" + s);
    }

    public void setInt(Object obj, int i) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Integer.valueOf(i), "(int)" + i);
    }

    public void setLong(Object obj, long l) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Long.valueOf(l), "(long)" + l);
    }

    public void setFloat(Object obj, float f) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Float.valueOf(f), "(float)" + f);
    }

    public void setDouble(Object obj, double d) throws IllegalArgumentException, IllegalAccessException {
        write(obj, Double.valueOf(d), "(double)" + d);
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return lazy.internal.Annotations.find(getDeclaredAnnotations(), annotationClass);
    }

    public Annotation[] getDeclaredAnnotations() {
        if (annotationCache == null) {
            annotationCache = lazy.internal.AnnotationParser.parse(annotations, clazz);
        }
        return annotationCache.clone();
    }
}
