package s2;

import java.io.Serializable;

public class Casts {
    interface Animal { }
    interface Pet { }
    static class Dog implements Animal, Pet { }
    static class Puppy extends Dog { }
    static class Cat implements Animal { }
    static class Rock { }

    static void check(String label, boolean b) { System.out.println(label + " = " + b); }

    static String cast(Object o, int kind) {
        try {
            switch (kind) {
                case 0: { Dog d = (Dog) o; return "Dog ok " + (d != null); }
                case 1: { Pet p = (Pet) o; return "Pet ok " + (p != null); }
                case 2: { Animal a = (Animal) o; return "Animal ok " + (a != null); }
                case 3: { String[] s = (String[]) o; return "String[] ok " + (s != null); }
                case 4: { Object[] s = (Object[]) o; return "Object[] ok " + (s != null); }
                case 5: { int[] s = (int[]) o; return "int[] ok " + (s != null); }
                default: return "bad kind";
            }
        } catch (ClassCastException e) {
            return "CCE " + e.getClass().getName();
        }
    }

    public static void main(String[] args) {
        Object dog = new Dog(), puppy = new Puppy(), cat = new Cat(), rock = new Rock();
        check("dog instanceof Dog", dog instanceof Dog);
        check("dog instanceof Puppy", dog instanceof Puppy);
        check("puppy instanceof Dog", puppy instanceof Dog);
        check("puppy instanceof Pet", puppy instanceof Pet);
        check("cat instanceof Pet", cat instanceof Pet);
        check("cat instanceof Animal", cat instanceof Animal);
        check("rock instanceof Animal", rock instanceof Animal);
        check("rock instanceof Object", rock instanceof Object);
        Object nul = null;
        check("null instanceof Object", nul instanceof Object);
        check("null instanceof Dog", nul instanceof Dog);
        System.out.println(cast(dog, 0));
        System.out.println(cast(puppy, 0));
        System.out.println(cast(cat, 0));
        System.out.println(cast(null, 0));
        System.out.println(cast(dog, 1));
        System.out.println(cast(cat, 1));
        System.out.println(cast(cat, 2));
        System.out.println(cast(rock, 2));
        System.out.println(cast("str", 0));
        System.out.println(cast(new Integer(1), 0));

        Object sarr = new String[2];
        Object oarr = new Object[2];
        Object iarr = new int[2];
        Object darr = new Dog[1][1];
        Object parr = new Puppy[1];
        check("String[] instanceof Object[]", sarr instanceof Object[]);
        check("String[] instanceof String[]", sarr instanceof String[]);
        check("Object[] instanceof String[]", oarr instanceof String[]);
        check("int[] instanceof Object", iarr instanceof Object);
        check("int[] instanceof Object[]", iarr instanceof Object[]);
        check("int[] instanceof long[]", iarr instanceof long[]);
        check("Object[] instanceof Cloneable", oarr instanceof Cloneable);
        check("Object[] instanceof Serializable", oarr instanceof Serializable);
        check("int[] instanceof Cloneable", iarr instanceof Cloneable);
        check("int[] instanceof Serializable", iarr instanceof Serializable);
        check("Dog[][] instanceof Object[]", darr instanceof Object[]);
        check("Dog[][] instanceof Dog[]", darr instanceof Dog[]);
        check("Dog[][] instanceof Dog[][]", darr instanceof Dog[][]);
        check("Dog[][] instanceof Animal[][]", darr instanceof Animal[][]);
        check("Puppy[] instanceof Dog[]", parr instanceof Dog[]);
        check("Puppy[] instanceof Pet[]", parr instanceof Pet[]);
        check("Puppy[] instanceof Cat[]", parr instanceof Cat[]);
        check("Puppy[] instanceof Serializable", parr instanceof Serializable);
        System.out.println(cast(sarr, 3));
        System.out.println(cast(oarr, 3));
        System.out.println(cast(sarr, 4));
        System.out.println(cast(iarr, 4));
        System.out.println(cast(iarr, 5));
        System.out.println(cast(sarr, 5));

        Object[] covariant = new String[1];
        try {
            covariant[0] = new Integer(5);
        } catch (ArrayStoreException e) {
            System.out.println("ArrayStoreException: " + e.getMessage());
        }
        covariant[0] = "fine";
        System.out.println(covariant[0]);
        Animal[] animals = new Dog[2];
        try {
            animals[0] = new Cat();
        } catch (ArrayStoreException e) {
            System.out.println("ArrayStoreException Cat into Dog[]");
        }
        animals[1] = new Puppy();
        System.out.println(animals[1] != null);
        Object[] withNull = new String[1];
        withNull[0] = null;
        System.out.println("null store ok");

        Object boxed = 5;
        check("5 instanceof Integer", boxed instanceof Integer);
        check("5 instanceof Number", boxed instanceof Number);
        check("5 instanceof Comparable", boxed instanceof Comparable);
        check("5 instanceof Long", boxed instanceof Long);
        try {
            Long l = (Long) boxed;
            System.out.println(l);
        } catch (ClassCastException e) {
            System.out.println("CCE " + e.getClass().getName());
        }
        Class<?> c = int[].class;
        System.out.println(c.getName() + " " + String[].class.getName() + " " + Dog[][].class.getName());
        System.out.println(Object[].class.isInstance(sarr) + " " + String[].class.isInstance(oarr));
        System.out.println(Dog.class.isAssignableFrom(Puppy.class) + " " + Puppy.class.isAssignableFrom(Dog.class));
        System.out.println(Object[].class.isAssignableFrom(String[].class));
        System.out.println("done");
    }
}
