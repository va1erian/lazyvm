package java.lang.reflect;

import java.lang.annotation.Annotation;

public final class Constructor<T> extends Executable {
    // Filled by the VM (docs/design/reflection.md); keep names and types.
    private Class<T> clazz;
    private Class<?>[] parameterTypes;
    private Class<?>[] exceptionTypes;
    private int modifiers;
    private int slot;
    private String signature;
    private byte[] annotations;
    private byte[] parameterAnnotations;

    private Annotation[] annotationCache;

    private Constructor() {
    }

    private static native Object newInstance0(Constructor<?> c, Object[] args);

    public Class<T> getDeclaringClass() {
        return clazz;
    }

    public String getName() {
        return clazz.getName();
    }

    public int getModifiers() {
        return modifiers;
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

    public TypeVariable<Constructor<T>>[] getTypeParameters() {
        lazy.internal.SignatureParser p = signature();
        return p == null ? lazy.internal.GenericTypes.<Constructor<T>>none()
                : p.<Constructor<T>>typeParameters();
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

    public boolean equals(Object obj) {
        if (!(obj instanceof Constructor)) {
            return false;
        }
        Constructor<?> other = (Constructor<?>) obj;
        if (clazz != other.clazz || other.parameterTypes.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (other.parameterTypes[i] != parameterTypes[i]) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        return clazz.getName().hashCode();
    }

    public String toString() {
        return modifierPrefix(modifiers & Modifier.constructorModifiers()) + clazz.getTypeName()
                + "(" + typeNames(parameterTypes, ",") + ")" + throwsClause();
    }

    public String toGenericString() {
        return modifierPrefix(modifiers & Modifier.constructorModifiers()) + typeParametersPrefix()
                + clazz.getTypeName() + genericParameterList() + genericThrowsClause();
    }

    /**
     * Creates and initializes an instance. Unboxing and widening of the arguments follow the
     * Javadoc; anything the constructor throws is wrapped in an InvocationTargetException.
     */
    @SuppressWarnings("unchecked")
    public T newInstance(Object... initargs) throws InstantiationException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException {
        if ((clazz.getModifiers() & (Modifier.ABSTRACT | Modifier.INTERFACE)) != 0) {
            throw new InstantiationException();
        }
        if (clazz.isEnum()) {
            throw new IllegalArgumentException("Cannot reflectively create enum objects");
        }
        Object[] converted = Method.convertArguments(parameterTypes, initargs);
        try {
            return (T) newInstance0(this, converted);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
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
