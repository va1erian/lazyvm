package lazy.internal;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/** The Type implementations that SignatureParser builds. */
public final class GenericTypes {
    private GenericTypes() {
    }

    @SuppressWarnings("unchecked")
    public static <D extends GenericDeclaration> TypeVariable<D>[] none() {
        return (TypeVariable<D>[]) new TypeVariable<?>[0];
    }

    static final class Parameterized implements ParameterizedType {
        private final Class<?> raw;
        private final Type[] arguments;
        private final Type owner;

        Parameterized(Class<?> raw, Type[] arguments, Type owner) {
            this.raw = raw;
            this.arguments = arguments;
            this.owner = owner;
        }

        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        public Type getRawType() {
            return raw;
        }

        public Type getOwnerType() {
            return owner;
        }

        public boolean equals(Object o) {
            if (!(o instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType p = (ParameterizedType) o;
            return raw.equals(p.getRawType()) && same(owner, p.getOwnerType())
                    && Arrays.equals(arguments, p.getActualTypeArguments());
        }

        private static boolean same(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }

        public int hashCode() {
            return Arrays.hashCode(arguments) ^ (owner == null ? 0 : owner.hashCode()) ^ raw.hashCode();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (owner == null) {
                sb.append(raw.getName());
            } else {
                appendOwned(sb);
            }
            if (arguments.length > 0) {
                sb.append('<');
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(arguments[i].getTypeName());
                }
                sb.append('>');
            }
            return sb.toString();
        }

        /** "Owner$Inner": the inner part is the raw name minus the owner's raw name when the owner is generic. */
        private void appendOwned(StringBuilder sb) {
            sb.append(owner instanceof Class ? ((Class<?>) owner).getName() : owner.toString()).append('$');
            if (owner instanceof Parameterized) {
                String ownerRaw = ((Parameterized) owner).raw.getName() + "$";
                String name = raw.getName();
                sb.append(name.startsWith(ownerRaw) ? name.substring(ownerRaw.length()) : name);
            } else {
                sb.append(raw.getSimpleName());
            }
        }
    }

    /** A declared type variable; its bounds are parsed on first use so that bounds may mention it. */
    static final class Variable<D extends GenericDeclaration> implements TypeVariable<D> {
        private final String name;
        private final D declaration;
        private final SignatureParser source;
        private final int boundsPos;
        private Type[] bounds;

        Variable(String name, D declaration, SignatureParser source, int boundsPos) {
            this.name = name;
            this.declaration = declaration;
            this.source = source;
            this.boundsPos = boundsPos;
        }

        public Type[] getBounds() {
            if (bounds == null) {
                bounds = source == null ? new Type[] {Object.class} : source.bounds(boundsPos);
            }
            return bounds.clone();
        }

        public D getGenericDeclaration() {
            return declaration;
        }

        public String getName() {
            return name;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Variable)) {
                return false;
            }
            Variable<?> v = (Variable<?>) o;
            return name.equals(v.name) && (declaration == null ? v.declaration == null
                    : declaration.equals(v.declaration));
        }

        public int hashCode() {
            return (declaration == null ? 0 : declaration.hashCode()) ^ name.hashCode();
        }

        public String toString() {
            return name;
        }
    }

    static final class Wildcard implements WildcardType {
        private final Type[] upper;
        private final Type[] lower;

        Wildcard(Type[] upper, Type[] lower) {
            this.upper = upper;
            this.lower = lower;
        }

        public Type[] getUpperBounds() {
            return upper.clone();
        }

        public Type[] getLowerBounds() {
            return lower.clone();
        }

        public boolean equals(Object o) {
            if (!(o instanceof WildcardType)) {
                return false;
            }
            WildcardType w = (WildcardType) o;
            return Arrays.equals(lower, w.getLowerBounds()) && Arrays.equals(upper, w.getUpperBounds());
        }

        public int hashCode() {
            return Arrays.hashCode(lower) ^ Arrays.hashCode(upper);
        }

        public String toString() {
            if (lower.length > 0) {
                return "? super " + lower[0].getTypeName();
            }
            if (upper.length == 0 || upper[0] == Object.class) {
                return "?";
            }
            return "? extends " + upper[0].getTypeName();
        }
    }

    static final class ArrayOf implements GenericArrayType {
        private final Type component;

        ArrayOf(Type component) {
            this.component = component;
        }

        public Type getGenericComponentType() {
            return component;
        }

        public boolean equals(Object o) {
            return o instanceof GenericArrayType
                    && component.equals(((GenericArrayType) o).getGenericComponentType());
        }

        public int hashCode() {
            return component.hashCode();
        }

        public String toString() {
            return component.getTypeName() + "[]";
        }
    }
}
