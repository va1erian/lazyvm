package java.lang;

/** The mirror of a loaded class; the VM creates every instance. */
public final class Class<T> implements java.io.Serializable, java.lang.reflect.GenericDeclaration,
        java.lang.reflect.Type, java.lang.reflect.AnnotatedElement {
    private transient int vmClassId;
    private transient java.lang.annotation.Annotation[] declaredAnnotations;
    private transient java.lang.annotation.Annotation[] allAnnotations;

    private Class() {
    }

    public String toString() {
        String kind = isInterface() ? "interface " : (isPrimitive() ? "" : "class ");
        return kind + getName();
    }

    public native String getName();

    public native boolean isInstance(Object obj);

    public native boolean isAssignableFrom(Class<?> cls);

    public native boolean isInterface();

    public native boolean isArray();

    public native boolean isPrimitive();

    public native Class<? super T> getSuperclass();

    public native Class<?> getComponentType();

    public native int getModifiers();

    static native Class<?> getPrimitiveClass(String name);

    private static native Class<?> forName0(String name, boolean initialize)
            throws ClassNotFoundException;

    public static Class<?> forName(String className) throws ClassNotFoundException {
        return forName0(className, true);
    }

    /**
     * LazyVM has one application loader; a null loader means the boot
     * loader, which only sees the class library.
     */
    public static Class<?> forName(String name, boolean initialize, ClassLoader loader)
            throws ClassNotFoundException {
        if (loader == null) {
            Class<?> c = forName0(name, false);
            if (!lazy.internal.VM.isBootClass(c)) {
                throw new ClassNotFoundException(name);
            }
        }
        return forName0(name, initialize);
    }

    /** Null for classes of the class library, as for any boot class. */
    public ClassLoader getClassLoader() {
        return lazy.internal.VM.isBootClass(this) ? null : ClassLoader.getSystemClassLoader();
    }

    /**
     * Finds a resource next to this class: a relative name is resolved in
     * this class's package, a name starting with '/' from the root.
     */
    public java.io.InputStream getResourceAsStream(String name) {
        byte[] data = lazy.internal.VM.getResource(resolveName(name));
        return data == null ? null : new java.io.ByteArrayInputStream(data);
    }

    private String resolveName(String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        Class<?> c = this;
        while (c.isArray()) {
            c = c.getComponentType();
        }
        String binary = c.getName();
        int dot = binary.lastIndexOf('.');
        return dot < 0 ? name : binary.substring(0, dot).replace('.', '/') + "/" + name;
    }

    /** `-ea` turns assertions on for application classes. */
    public native boolean desiredAssertionStatus();

    /** The constants of an enum class in declaration order, or null. */
    public T[] getEnumConstants() {
        if (!isEnum()) {
            return null;
        }
        return getEnumConstants0();
    }

    /** Calls the enum's static `values()`, which returns a fresh copy. */
    private native T[] getEnumConstants0();

    public boolean isEnum() {
        return (getModifiers() & 0x4000) != 0;
    }

    public boolean isAnnotation() {
        return (getModifiers() & 0x2000) != 0;
    }

    @SuppressWarnings("unchecked")
    public T cast(Object obj) {
        if (obj != null && !isInstance(obj)) {
            throw new ClassCastException("Cannot cast " + obj.getClass().getName() + " to " + getName());
        }
        return (T) obj;
    }

    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> clazz) {
        if (clazz.isAssignableFrom(this)) {
            return (Class<? extends U>) this;
        }
        throw new ClassCastException(toString());
    }

    /** The unqualified name; "" for anonymous classes. */
    public String getSimpleName() {
        if (isArray()) {
            return getComponentType().getSimpleName() + "[]";
        }
        String name = getName();
        String s = name.substring(name.lastIndexOf('.') + 1);
        int dollar = s.lastIndexOf('$');
        if (dollar < 0 || dollar == s.length() - 1) {
            return s;
        }
        // Anonymous classes are Outer$1; local ones are Outer$1Name.
        int i = dollar + 1;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return s.substring(i);
    }

    public String getTypeName() {
        return isArray() ? getComponentType().getTypeName() + "[]" : getName();
    }

    public String getCanonicalName() {
        return ClassReflection.canonicalName(this);
    }

    public boolean isSynthetic() {
        return (getModifiers() & 0x1000) != 0;
    }

    // ---- members (docs/design/reflection.md) ----------------------------

    private native java.lang.reflect.Field[] getDeclaredFields0(boolean publicOnly);

    private native java.lang.reflect.Method[] getDeclaredMethods0(boolean publicOnly);

    private native java.lang.reflect.Constructor<T>[] getDeclaredConstructors0(boolean publicOnly);

    private native Class<?>[] getInterfaces0();

    private native String getGenericSignature0();

    private native byte[] getRawAnnotations();

    private native Class<?> getDeclaringClass0();

    java.lang.reflect.Field[] declaredFields(boolean publicOnly) {
        return getDeclaredFields0(publicOnly);
    }

    java.lang.reflect.Method[] declaredMethods(boolean publicOnly) {
        return getDeclaredMethods0(publicOnly);
    }

    public java.lang.reflect.Field[] getDeclaredFields() {
        return getDeclaredFields0(false);
    }

    public java.lang.reflect.Method[] getDeclaredMethods() {
        return getDeclaredMethods0(false);
    }

    public java.lang.reflect.Constructor<?>[] getDeclaredConstructors() {
        return getDeclaredConstructors0(false);
    }

    public java.lang.reflect.Field[] getFields() {
        return ClassReflection.publicFields(this);
    }

    public java.lang.reflect.Method[] getMethods() {
        return ClassReflection.publicMethods(this);
    }

    public java.lang.reflect.Constructor<?>[] getConstructors() {
        return getDeclaredConstructors0(true);
    }

    public Class<?>[] getInterfaces() {
        return getInterfaces0();
    }

    public java.lang.reflect.Field getField(String name) throws NoSuchFieldException {
        java.lang.reflect.Field f = ClassReflection.pickField(getFields(), name);
        if (f == null) {
            throw new NoSuchFieldException(name);
        }
        return f;
    }

    public java.lang.reflect.Field getDeclaredField(String name) throws NoSuchFieldException {
        java.lang.reflect.Field f = ClassReflection.pickField(getDeclaredFields(), name);
        if (f == null) {
            throw new NoSuchFieldException(name);
        }
        return f;
    }

    public java.lang.reflect.Method getMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return find(ClassReflection.pick(getMethods(), nonNull(name), params(parameterTypes)), name, parameterTypes);
    }

    public java.lang.reflect.Method getDeclaredMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return find(ClassReflection.pick(getDeclaredMethods(), nonNull(name), params(parameterTypes)),
                name, parameterTypes);
    }

    public java.lang.reflect.Constructor<T> getConstructor(Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return find(ClassReflection.pickConstructor(getDeclaredConstructors0(true), params(parameterTypes)),
                "<init>", parameterTypes);
    }

    public java.lang.reflect.Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return find(ClassReflection.pickConstructor(getDeclaredConstructors0(false), params(parameterTypes)),
                "<init>", parameterTypes);
    }

    private static String nonNull(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        return name;
    }

    private static Class<?>[] params(Class<?>[] types) {
        return types == null ? new Class<?>[0] : types;
    }

    private <M> M find(M found, String name, Class<?>[] types) throws NoSuchMethodException {
        if (found == null) {
            throw new NoSuchMethodException(ClassReflection.describe(this, name, params(types)));
        }
        return found;
    }

    /** Creates an instance through the no-argument constructor; its checked exceptions pass through unwrapped. */
    public T newInstance() throws InstantiationException, IllegalAccessException {
        java.lang.reflect.Constructor<T> c;
        try {
            c = getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new InstantiationException(getName());
        }
        try {
            return c.newInstance();
        } catch (java.lang.reflect.InvocationTargetException e) {
            ClassReflection.<RuntimeException>sneakyThrow(e.getTargetException());
            return null;
        }
    }

    // ---- nesting ---------------------------------------------------------

    public Class<?> getDeclaringClass() {
        return getDeclaringClass0();
    }

    public Class<?> getEnclosingClass() {
        return getDeclaringClass0();
    }

    public boolean isMemberClass() {
        return getDeclaringClass0() != null;
    }

    public boolean isAnonymousClass() {
        return ClassReflection.isAnonymous(this);
    }

    public boolean isLocalClass() {
        return ClassReflection.isLocal(this);
    }

    // ---- generics ----------------------------------------------------------

    private lazy.internal.SignatureParser signature() {
        String sig = getGenericSignature0();
        return sig == null ? null : new lazy.internal.SignatureParser(sig, this, this);
    }

    public java.lang.reflect.Type getGenericSuperclass() {
        if (isInterface()) {
            return null;
        }
        lazy.internal.SignatureParser p = signature();
        return p == null ? getSuperclass() : p.classSuperclass();
    }

    public java.lang.reflect.Type[] getGenericInterfaces() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? getInterfaces() : p.classInterfaces();
    }

    public java.lang.reflect.TypeVariable<Class<T>>[] getTypeParameters() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? lazy.internal.GenericTypes.<Class<T>>none() : p.<Class<T>>typeParameters();
    }

    // ---- annotations ---------------------------------------------------------

    public java.lang.annotation.Annotation[] getDeclaredAnnotations() {
        if (declaredAnnotations == null) {
            declaredAnnotations = lazy.internal.AnnotationParser.parse(getRawAnnotations(), this);
        }
        return declaredAnnotations.clone();
    }

    public java.lang.annotation.Annotation[] getAnnotations() {
        if (allAnnotations == null) {
            allAnnotations = ClassReflection.withInherited(this, getDeclaredAnnotations());
        }
        return allAnnotations.clone();
    }

    public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationClass) {
        return lazy.internal.Annotations.find(getAnnotations(), annotationClass);
    }

    public <A extends java.lang.annotation.Annotation> A[] getAnnotationsByType(Class<A> annotationClass) {
        A[] own = lazy.internal.Annotations.byType(getDeclaredAnnotations(), annotationClass);
        Class<?> sup = getSuperclass();
        if (own.length == 0 && sup != null
                && annotationClass.isAnnotationPresent(java.lang.annotation.Inherited.class)) {
            return sup.getAnnotationsByType(annotationClass);
        }
        return own;
    }
}
