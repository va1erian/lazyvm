package java.lang.reflect;

import java.lang.annotation.Annotation;

public final class Method extends Executable {
    // Filled by the VM (docs/design/reflection.md); keep names and types.
    private Class<?> clazz;
    private String name;
    private Class<?>[] parameterTypes;
    private Class<?> returnType;
    private Class<?>[] exceptionTypes;
    private int modifiers;
    private int slot;
    private String signature;
    private byte[] annotations;
    private byte[] parameterAnnotations;
    private byte[] annotationDefault;

    private Annotation[] annotationCache;

    private Method() {
    }

    private static native Object invoke0(Method m, Object obj, Object[] args);

    public Class<?> getDeclaringClass() {
        return clazz;
    }

    public String getName() {
        return name;
    }

    public int getModifiers() {
        return modifiers;
    }

    public Class<?> getReturnType() {
        return returnType;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes.clone();
    }

    Class<?>[] rawParameterTypes() {
        return parameterTypes;
    }

    public Class<?>[] getExceptionTypes() {
        return exceptionTypes.clone();
    }

    Class<?>[] rawExceptionTypes() {
        return exceptionTypes;
    }

    String signatureText() {
        return signature;
    }

    byte[] rawParameterAnnotations() {
        return parameterAnnotations;
    }

    public TypeVariable<Method>[] getTypeParameters() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? lazy.internal.GenericTypes.<Method>none() : p.<Method>typeParameters();
    }

    public Type getGenericReturnType() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? returnType : p.methodReturn();
    }

    public Type[] getGenericParameterTypes() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? getParameterTypes() : p.methodParameters();
    }

    public Type[] getGenericExceptionTypes() {
        lazy.internal.SignatureParser p = signature();
        if (p == null) {
            return getExceptionTypes();
        }
        Type[] thrown = p.methodExceptions();
        return thrown.length == 0 ? getExceptionTypes() : thrown;
    }

    public boolean isBridge() {
        return (modifiers & Modifier.BRIDGE) != 0;
    }

    public boolean isDefault() {
        int mask = Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC;
        return (modifiers & mask) == Modifier.PUBLIC && clazz.isInterface();
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof Method)) {
            return false;
        }
        Method other = (Method) obj;
        if (clazz != other.clazz || !name.equals(other.name) || returnType != other.returnType) {
            return false;
        }
        return sameParameters(other.parameterTypes);
    }

    private boolean sameParameters(Class<?>[] others) {
        if (others.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < others.length; i++) {
            if (others[i] != parameterTypes[i]) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        return clazz.getName().hashCode() ^ name.hashCode();
    }

    public String toString() {
        return modifierPrefix(modifiers & Modifier.methodModifiers()) + (isDefault() ? "default " : "")
                + returnType.getTypeName() + " "
                + clazz.getTypeName() + "." + name + "(" + typeNames(parameterTypes, ",") + ")"
                + throwsClause();
    }

    public String toGenericString() {
        String mods = modifierPrefix(modifiers & Modifier.methodModifiers());
        return mods + (isDefault() ? "default " : "") + typeParametersPrefix()
                + getGenericReturnType().getTypeName() + " " + clazz.getTypeName() + "." + name
                + genericParameterList() + genericThrowsClause();
    }

    /**
     * Calls the method. Unboxing and widening follow the Javadoc; anything the method throws is
     * wrapped in an InvocationTargetException.
     */
    public Object invoke(Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (!Modifier.isStatic(modifiers)) {
            if (obj == null) {
                throw new NullPointerException();
            }
            if (!clazz.isInstance(obj)) {
                throw new IllegalArgumentException("object is not an instance of declaring class");
            }
        }
        Object[] converted = convertArguments(parameterTypes, args);
        if (Modifier.isStatic(modifiers)) {
            Convert.initialize(clazz);
        }
        try {
            return invoke0(this, obj, converted);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    /** Checks the count and converts each argument to its parameter's exact type. */
    static Object[] convertArguments(Class<?>[] types, Object[] args) {
        int n = args == null ? 0 : args.length;
        if (n != types.length) {
            throw new IllegalArgumentException("wrong number of arguments");
        }
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            Object v = Convert.to(args[i], types[i]);
            if (v == Convert.FAIL) {
                if (args[i] == null) {
                    throw new IllegalArgumentException();
                }
                throw new IllegalArgumentException("argument type mismatch");
            }
            out[i] = v;
        }
        return out;
    }

    public Object getDefaultValue() {
        return lazy.internal.AnnotationParser.parseDefault(annotationDefault, clazz, returnType);
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

    public Annotation[][] getParameterAnnotations() {
        return copyParameterAnnotations();
    }
}
