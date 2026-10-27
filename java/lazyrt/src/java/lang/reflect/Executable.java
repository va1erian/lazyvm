package java.lang.reflect;

import java.lang.annotation.Annotation;

/** What methods and constructors share: parameters, exceptions, annotations and the printed forms. */
public abstract class Executable extends AccessibleObject implements Member, GenericDeclaration {
    Executable() {
    }

    public abstract Class<?> getDeclaringClass();

    public abstract String getName();

    public abstract int getModifiers();

    public abstract Class<?>[] getParameterTypes();

    public int getParameterCount() {
        return rawParameterTypes().length;
    }

    public Type[] getGenericParameterTypes() {
        return getParameterTypes();
    }

    public Parameter[] getParameters() {
        Class<?>[] types = rawParameterTypes();
        Parameter[] out = new Parameter[types.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Parameter("arg" + i, 0, this, i);
        }
        return out;
    }

    public abstract Class<?>[] getExceptionTypes();

    public Type[] getGenericExceptionTypes() {
        return getExceptionTypes();
    }

    public abstract TypeVariable<?>[] getTypeParameters();

    public abstract Annotation[][] getParameterAnnotations();

    public boolean isVarArgs() {
        return (getModifiers() & Modifier.VARARGS) != 0;
    }

    public boolean isSynthetic() {
        return (getModifiers() & Modifier.SYNTHETIC) != 0;
    }

    public abstract String toGenericString();

    abstract Class<?>[] rawParameterTypes();

    abstract Class<?>[] rawExceptionTypes();

    /** The signature attribute text, or null. */
    abstract String signatureText();

    abstract byte[] rawParameterAnnotations();

    private Annotation[][] parameterCache;

    /** The parameter annotations, parsed once; the caller gets copies of the arrays. */
    Annotation[][] copyParameterAnnotations() {
        if (parameterCache == null) {
            parameterCache = lazy.internal.AnnotationParser.parseParameters(
                    rawParameterAnnotations(), getDeclaringClass(), rawParameterTypes().length);
        }
        Annotation[][] out = new Annotation[parameterCache.length][];
        for (int i = 0; i < out.length; i++) {
            out[i] = parameterCache[i].clone();
        }
        return out;
    }

    /** The parsed signature of this member, or null when it has none. */
    lazy.internal.SignatureParser signature() {
        String text = signatureText();
        return text == null ? null : new lazy.internal.SignatureParser(text, this, getDeclaringClass());
    }

    static String typeNames(Class<?>[] types, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(types[i].getTypeName());
        }
        return sb.toString();
    }

    /** The throws clause (with a leading space) of the plain form, or "". */
    String throwsClause() {
        Class<?>[] ex = rawExceptionTypes();
        return ex.length == 0 ? "" : " throws " + typeNames(ex, ",");
    }

    /** The throws clause of the generic form: uses the generic exception types. */
    String genericThrowsClause() {
        Type[] ex = getGenericExceptionTypes();
        if (ex.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" throws ");
        for (int i = 0; i < ex.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ex[i].getTypeName());
        }
        return sb.toString();
    }

    /** "<T,U> " for a generic member, else "". */
    String typeParametersPrefix() {
        TypeVariable<?>[] params = getTypeParameters();
        if (params.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getTypeName());
        }
        return sb.append("> ").toString();
    }

    /** The generic parameter list in parentheses; the last one prints as T... for varargs. */
    String genericParameterList() {
        Type[] types = getGenericParameterTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String name = types[i].getTypeName();
            if (i == types.length - 1 && isVarArgs() && name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2) + "...";
            }
            sb.append(name);
        }
        return sb.append(')').toString();
    }

    /** The modifier text plus a trailing space, or "". */
    static String modifierPrefix(int modifiers) {
        String text = Modifier.toString(modifiers);
        return text.length() == 0 ? "" : text + " ";
    }
}
