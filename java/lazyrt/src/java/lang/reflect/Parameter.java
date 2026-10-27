package java.lang.reflect;

import java.lang.annotation.Annotation;

/** A parameter of a method or constructor; LazyVM does not read MethodParameters, so names are argN. */
public final class Parameter implements AnnotatedElement {
    private final String name;
    private final int modifiers;
    private final Executable executable;
    private final int index;

    Parameter(String name, int modifiers, Executable executable, int index) {
        this.name = name;
        this.modifiers = modifiers;
        this.executable = executable;
        this.index = index;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof Parameter)) {
            return false;
        }
        Parameter other = (Parameter) obj;
        return other.executable.equals(executable) && other.index == index;
    }

    public int hashCode() {
        return executable.hashCode() ^ index;
    }

    public boolean isNamePresent() {
        return false;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        String mods = Modifier.toString(getModifiers());
        if (mods.length() > 0) {
            sb.append(mods).append(' ');
        }
        String type = getParameterizedType().getTypeName();
        if (isVarArgs() && type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2) + "...";
        }
        return sb.append(type).append(' ').append(name).toString();
    }

    public Executable getDeclaringExecutable() {
        return executable;
    }

    public int getModifiers() {
        return modifiers;
    }

    public String getName() {
        return name;
    }

    public Type getParameterizedType() {
        Type[] generic = executable.getGenericParameterTypes();
        return generic.length == executable.getParameterCount() ? generic[index] : getType();
    }

    public Class<?> getType() {
        return executable.getParameterTypes()[index];
    }

    public boolean isImplicit() {
        return (modifiers & Modifier.MANDATED) != 0;
    }

    public boolean isSynthetic() {
        return (modifiers & Modifier.SYNTHETIC) != 0;
    }

    public boolean isVarArgs() {
        return executable.isVarArgs() && index == executable.getParameterCount() - 1;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return lazy.internal.Annotations.find(getDeclaredAnnotations(), annotationClass);
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    public Annotation[] getDeclaredAnnotations() {
        return executable.getParameterAnnotations()[index].clone();
    }
}
