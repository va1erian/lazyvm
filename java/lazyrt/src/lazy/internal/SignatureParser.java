package lazy.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * Reads the Signature attribute grammar of JVMS 4.7.9.1 into java.lang.reflect types.
 * Type variables resolve against `decl`, then the declarations that enclose it.
 */
public final class SignatureParser {
    private final String sig;
    private final GenericDeclaration decl;
    private final Class<?> holder;
    private int pos;

    public SignatureParser(String sig, GenericDeclaration decl, Class<?> holder) {
        this.sig = sig;
        this.decl = decl;
        this.holder = holder;
    }

    private java.lang.reflect.GenericSignatureFormatError bad() {
        return new java.lang.reflect.GenericSignatureFormatError();
    }

    // ---- entry points -------------------------------------------------

    public Type fieldType() {
        pos = 0;
        return type();
    }

    public Type classSuperclass() {
        pos = 0;
        skipFormals();
        return type();
    }

    public Type[] classInterfaces() {
        pos = 0;
        skipFormals();
        type();
        Buf out = new Buf();
        while (pos < sig.length()) {
            out.add(type());
        }
        return (Type[]) out.toArray(Type.class);
    }

    public Type[] methodParameters() {
        pos = 0;
        skipFormals();
        expect('(');
        Buf out = new Buf();
        while (peek() != ')') {
            out.add(type());
        }
        return (Type[]) out.toArray(Type.class);
    }

    public Type methodReturn() {
        pos = 0;
        skipFormals();
        skipParameters();
        return type();
    }

    public Type[] methodExceptions() {
        pos = 0;
        skipFormals();
        skipParameters();
        type();
        Buf out = new Buf();
        while (pos < sig.length() && sig.charAt(pos) == '^') {
            pos++;
            out.add(type());
        }
        return (Type[]) out.toArray(Type.class);
    }

    /** The formal type parameters; their bounds are parsed when asked for. */
    @SuppressWarnings("unchecked")
    public <D extends GenericDeclaration> TypeVariable<D>[] typeParameters() {
        pos = 0;
        if (peek() != '<') {
            return GenericTypes.none();
        }
        pos++;
        Buf out = new Buf();
        while (peek() != '>') {
            int colon = sig.indexOf(':', pos);
            if (colon < 0) {
                throw bad();
            }
            String name = sig.substring(pos, colon);
            pos = colon;
            out.add(new GenericTypes.Variable<D>(name, (D) decl, this, colon));
            skipBounds();
        }
        return (TypeVariable<D>[]) out.toArray(TypeVariable.class);
    }

    /** The bounds of the type parameter whose first ':' is at `at`. */
    Type[] bounds(int at) {
        SignatureParser p = new SignatureParser(sig, decl, holder);
        p.pos = at;
        Buf out = new Buf();
        while (p.peek() == ':') {
            p.pos++;
            if (p.peek() != ':') {
                out.add(p.type());
            }
        }
        return (Type[]) out.toArray(Type.class);
    }

    // ---- scanning ------------------------------------------------------

    private char peek() {
        if (pos >= sig.length()) {
            throw bad();
        }
        return sig.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw bad();
        }
        pos++;
    }

    private void skipFormals() {
        if (peek() != '<') {
            return;
        }
        int depth = 0;
        do {
            char c = peek();
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            pos++;
        } while (depth > 0);
    }

    private void skipBounds() {
        while (peek() == ':') {
            pos++;
            if (peek() != ':') {
                skipType();
            }
        }
    }

    private void skipParameters() {
        expect('(');
        while (peek() != ')') {
            skipType();
        }
        pos++;
    }

    private void skipType() {
        char c = peek();
        if (c == '[') {
            pos++;
            skipType();
        } else if (c == 'L') {
            int depth = 0;
            while (peek() != ';' || depth > 0) {
                char d = peek();
                depth += d == '<' ? 1 : d == '>' ? -1 : 0;
                pos++;
            }
            pos++;
        } else if (c == 'T') {
            pos = sig.indexOf(';', pos) + 1;
            if (pos == 0) {
                throw bad();
            }
        } else {
            pos++;
        }
    }

    // ---- types ---------------------------------------------------------

    private Type type() {
        char c = peek();
        switch (c) {
            case 'L': return classType();
            case 'T': return variable();
            case '[': return arrayType();
            default: return primitive(c);
        }
    }

    private Type primitive(char c) {
        pos++;
        switch (c) {
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'D': return double.class;
            case 'F': return float.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'S': return short.class;
            case 'Z': return boolean.class;
            case 'V': return void.class;
            default: throw bad();
        }
    }

    private Type arrayType() {
        pos++;
        Type component = type();
        if (component instanceof Class) {
            return java.lang.reflect.Array.newInstance((Class<?>) component, 0).getClass();
        }
        return new GenericTypes.ArrayOf(component);
    }

    private Type variable() {
        int end = sig.indexOf(';', pos);
        if (end < 0) {
            throw bad();
        }
        String name = sig.substring(pos + 1, end);
        pos = end + 1;
        for (GenericDeclaration d = decl; d != null; d = enclosing(d)) {
            TypeVariable<?>[] params = d.getTypeParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].getName().equals(name)) {
                    return params[i];
                }
            }
        }
        return new GenericTypes.Variable<GenericDeclaration>(name, null, null, 0);
    }

    private static GenericDeclaration enclosing(GenericDeclaration d) {
        if (d instanceof Class) {
            return ((Class<?>) d).getDeclaringClass();
        }
        if (d instanceof Method) {
            return ((Method) d).getDeclaringClass();
        }
        if (d instanceof Constructor) {
            return ((Constructor<?>) d).getDeclaringClass();
        }
        return null;
    }

    /** `Lpkg/Outer<args>.Inner<args>;`: a plain Class when nothing is parameterized. */
    private Type classType() {
        pos++;
        StringBuilder name = new StringBuilder();
        Type[] args = null;
        Type owner = null;
        boolean generic = false;
        while (true) {
            char c = peek();
            if (c == ';') {
                pos++;
                break;
            } else if (c == '<') {
                args = arguments();
                generic = true;
            } else if (c == '.') {
                pos++;
                owner = build(name.toString(), args, owner);
                name.append('$');
                args = null;
            } else {
                name.append(c == '/' ? '.' : c);
                pos++;
            }
        }
        if (!generic) {
            return load(name.toString());
        }
        return build(name.toString(), args, owner);
    }

    /** One link of the chain: a class with its arguments, owned by the previous link. */
    private Type build(String name, Type[] args, Type owner) {
        Class<?> raw = load(name);
        if (args == null && owner == null) {
            return raw;
        }
        Type realOwner = owner != null ? owner : raw.getDeclaringClass();
        return new GenericTypes.Parameterized(raw, args == null ? new Type[0] : args, realOwner);
    }

    private Type[] arguments() {
        expect('<');
        Buf out = new Buf();
        while (peek() != '>') {
            out.add(argument());
        }
        pos++;
        return (Type[]) out.toArray(Type.class);
    }

    private Type argument() {
        char c = peek();
        if (c == '*') {
            pos++;
            return new GenericTypes.Wildcard(new Type[] {Object.class}, new Type[0]);
        }
        if (c == '+') {
            pos++;
            return new GenericTypes.Wildcard(new Type[] {type()}, new Type[0]);
        }
        if (c == '-') {
            pos++;
            return new GenericTypes.Wildcard(new Type[] {Object.class}, new Type[] {type()});
        }
        return type();
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, holder.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new TypeNotPresentException(name, e);
        }
    }
}
