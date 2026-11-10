# Doze: a small language for LazyOS

Doze is a proposed source language with Smalltalk's message syntax and
Java's types. It compiles to version 52 class files that run on LazyVM and
on any Java 8 VM, and it calls the Java API directly. Its compiler, `dozec`,
is a Rust program, so a LazyOS machine builds programs for LazyVM in
milliseconds without a Java compiler. It is also the language of LazyRAD
(section 11); `async` and `await` make LazyOS's asynchronous APIs read like
blocking code ([`doze-async.md`](doze-async.md)); and the same front end
serves game hosts such as miniscent as an embedded script language
([`doze-script.md`](doze-script.md)). It is a proposal: nothing here is
decided until the owner confirms it in [`decisions.md`](decisions.md), and
the stage it implies is S16 in [`plan-2.md`](plan-2.md).

## 1. Why, and why not something else

[`compiler.md`](compiler.md) weighs three ways to compile on LazyOS: a stock
JDK (A), javac 8 on LazyVM (B), and our own Java compiler (C, turned down as
months of work and a second javac). Doze is not C: it is a much smaller
language, chosen so that its compiler is small.

| | A: JDK on LazyOS | B: javac 8 on LazyVM | Doze |
|---|---|---|---|
| What runs the compiler | HotSpot | LazyVM, interpreted | native Rust |
| Size on LazyOS | hundreds of MiB | `tools.jar` plus `javax.*` additions to `lazyrt` | one static binary under 2,000,000 bytes, stripped and uncompressed |
| Language | all of Java | all of Java 8 | Smalltalk messages over Java types |
| Ready | when HotSpot runs on LazyOS | after S4, S5, S8, S11 and the D6 "Compiler support" row | needs nothing from LazyVM beyond what is done |

They are not exclusive: A or B stay the way to build existing Java code.

## 2. Goals and non-goals

- **Smalltalk's syntax.** Everything is a message: unary (`list size`),
  binary (`a + b`) and keyword (`map put: k value: v`), with no dots and no
  parentheses around arguments. Blocks are `[:x | ...]`, cascades are `;`,
  and `ifTrue:`, `whileTrue:`, `to:do:` and `on:do:` are messages, not
  statements. 21 reserved words against Java's 53.
- **Java's types and semantics.** Static types, `int x := 0`, classes with
  `extends`/`implements`, generics used as in Java, Java's arithmetic and
  string conversion, and Java's operator precedence, not Smalltalk's
  strict left to right (miniscent found `2 + 3 * 4 = 20` a trap).
- **First-class Java API.** Any public class of the Java SE 8 API, `lazy.*`
  or a jar on the class path is used directly, with overloads, varargs,
  boxing and functional interfaces (section 6).
- **Fast to compile.** One pass collects declarations, one checks and
  emits; the class path is read lazily. Target: a 1,000-line program in
  under 200 ms on LazyOS under QEMU, measured, not assumed (S16-10).
- **No runtime library.** Programs need only the Java SE 8 API `lazyrt`
  provides (and `lazy.async` once they `await`, doze-async.md section 4).
  A Doze jar runs on the oracle JVM unchanged, which is how it is tested.
- **Plain class files.** Version 52 with `StackMapTable`, passing LazyVM's
  verifier and the oracle's `-Xverify:all`; `invokedynamic` only through
  `LambdaMetafactory.metafactory` (D1).

Non-goals: compiling Java or Smalltalk source; an image, a browser or
`become:`; declaring generic classes or methods; inner and anonymous
classes (blocks replace them); checked exceptions; non-local return from a
block that outlives its method.

## 3. A taste

```doze
import java.util.*
import java.util.stream.Collectors

// A file's top-level statements form its main method; `args` is in scope.
var words := {"lazy", "os", "doze", "vm", "lazy"}
Map<String, Integer> counts := LinkedHashMap new
words do: [:w | counts merge: w with: 1 using: [:a :b | a + b]]
println: "counts: ${counts}"

var longWords := ((words stream filter: [:w | w length > 2])
    map: [:w | w toUpperCase])
    collect: Collectors toList
println: longWords

println: (describe: (Shape of: args size))

String describe: Object o {
    (o is: Circle) ifTrue: [^ "circle r=${o r}"]
    (o is: Square) ifTrue: [^ "square side=${o side}"]
    o isNil ifTrue: [^ "nothing"]
    ^ "unknown"
}

interface Shape {
    double area
    static Shape of: int n {
        ^ n = 0 ifTrue: [Circle new: 1.0] ifFalse: [Square new: n]
    }
}

class Circle implements Shape {
    public final double r
    new: double radius { r := radius }
    double area { ^ Math PI * r * r }
}

class Square implements Shape {
    public final double side
    new: double s { side := s }
    double area { ^ side * side }
}
```

`dozec run demo.dz` compiles the four classes and runs them with `lazyvm`.

## 4. Syntax

### 4.1 Lexical

- Identifiers, numbers (with `_`, `0x`, `0b`, `L`, `f`, `d`), `'c'` chars and
  escapes as in Java (JLS 3.10). Strings are `"..."` with `${expr}`
  interpolation (`\$` is a dollar). Comments are `//` and `/* */`.
- A keyword part is an identifier immediately followed by `:` (`put:`).
  `:=` is assignment; `^` at the start of a statement returns, and
  elsewhere is Java's exclusive or.
- **Statements end at a newline or `.`**, Smalltalk's separator. A `.`
  between two identifiers with no space (`java.util.List`) is part of a
  qualified name, never a separator. A statement continues on the next
  line when its line ends with a binary operator, a keyword part or `:=`,
  or when the next line starts with a keyword part, a binary operator or
  `;`, so long keyword messages are written one keyword per line.
- Reserved words: `package import class interface enum extends implements
  static final public private abstract async await var self super nil
  true false void`, and the primitive type names.

### 4.2 Messages and precedence

From tightest to loosest:

1. **Unary messages**, left to right: `list size`, `Math PI`,
   `ArrayList new`, `x not`.
2. **Binary operators** with Java's precedence among themselves (JLS 15):
   `* / %`, `+ -`, `<< >> >>>`, `< > <= >=`, `= ~= == ~~`, `&`, `^`, `|`,
   `&&`, `||`. `=` and `~=` are equality (`Objects.equals`), `==` and `~~`
   identity, as in Smalltalk; the compiler warns on `==` between strings
   or boxed numbers.
3. **Keyword messages**: `dict put: k value: v`. The receiver and each
   argument are binary expressions; a keyword message inside another needs
   parentheses.
4. **`await`**, prefix, over a whole keyword message: `await c write: data`.
5. **Cascade** `;`: `list add: 1; add: 2; yourself` sends each message to
   the receiver of the first one; the value is the last message's.
6. **Assignment** `x := e`, and declaration `Type x := e` or `var x := e`.
   Assigning to an undeclared name is an error, so a typo never declares a
   variable.

A message with no receiver (`say: "hi"`, `println: x`, `player`) calls a
top-level method of the files being compiled; a bare identifier that is no
variable is a unary one.

### 4.3 Grammar

```ebnf
file      = [ "package" qname ] { "import" qname [ ".*" ] } { top } ;
top       = typeDecl | method | stmt ;
typeDecl  = { annotation } { modifier } ( "class" | "interface" | "enum" ) Ident
            [ "extends" type ] [ "implements" type { "," type } ]
            "{" [ Ident { "," Ident } ] { field | method | ctor } "}" ;
field     = { annotation } { modifier } ( type | "var" ) Ident [ ":=" expr ] ;
method    = { annotation } { modifier } ( type | "void" ) pattern ( body | (* abstract *) ) ;
ctor      = { modifier } ( "new" | "new:" param { Keyword param } ) body ;
pattern   = Ident | kwPattern ;
kwPattern = Keyword param { Keyword param } ;
param     = { annotation } type Ident [ "..." ] ;
body      = "{" { stmt } "}" ;
stmt      = ( type | "var" ) Ident ":=" expr | Ident ":=" expr | "^" [ expr ] | expr ;
expr      = [ "await" ] cascade ;
cascade   = keywordMsg { ";" ( Ident | Keyword binary { Keyword binary } ) } ;
keywordMsg = binary [ Keyword binary { Keyword binary } ] | Keyword binary { Keyword binary } ;
binary    = unary { BinOp unary } ;               (* Java precedence, 4.2 *)
unary     = primary { Ident } ;
primary   = literal | Ident | "self" | "super" | "nil" | "true" | "false"
          | type (* as a receiver: class-side messages *) | "(" expr ")" | block
          | "{" [ expr { "," expr } ] "}" | "{" expr "->" expr { "," expr "->" expr } "}" ;
block     = [ "async" ] "[" [ { ":" Ident } "|" ] { stmt } "]" ;
type      = qname [ "<" type { "," type } ">" ] { "[]" } | primitive { "[]" } ;
modifier  = "public" | "private" | "static" | "final" | "abstract" | "async" ;
annotation = "@" qname [ "(" ( literal | Ident "=" literal { "," Ident "=" literal } ) ")" ] ;
```

`{a, b}` is a list and `{k -> v}` a map; `{}` is an empty list and an
empty map is `LinkedHashMap new`. A block's value is its last expression.

### 4.4 Built-in messages

These are compiled inline when their block arguments are literal blocks
(Smalltalk does the same), so they cost no lambda and `^` inside them
returns from the enclosing method. With any other receiver or argument they
are ordinary sends.

| Message | Receiver | Meaning |
|---|---|---|
| `ifTrue:` `ifFalse:` `ifTrue:ifFalse:` `ifFalse:ifTrue:` | `boolean` | conditionals; with both branches, an expression |
| `and:` `or:` `not` | `boolean` | short-circuit and negation (`&&`, `\|\|` also exist) |
| `whileTrue:` `whileFalse:` | literal block of `boolean` | loops |
| `to:do:` `to:by:do:` | `int` or `long` | counting loop, inclusive (`0 to: n - 1 do: [:i \| ...]`) |
| `timesRepeat:` | `int` | a loop with no index |
| `do:` | `Iterable`, array, `Map` (`[:k :v \| ...]`) | iteration |
| `isNil` `notNil` `ifNil:` `ifNotNil:` `ifNil:ifNotNil:` | any reference | null tests; `ifNotNil: [:v \| ...]` binds the value |
| `is:` `as:` | any; argument is a type | `instanceof` and cast; a local tested with `is:` has that type inside the inlined `ifTrue:` block |
| `on:do:` (repeatable) `ensure:` | literal block | `try`/`catch` and `try`/`finally` |
| `signal` | `Throwable` | throws it |
| `at:` `at:put:` `size` | array, `List` (`at:`/`at:put:` only) | 0-based indexing, as in Java |
| `yourself` | any | the receiver, to end a cascade |
| `new`, `new:...` | a type | a constructor; for an array type, `int[] new: 10` |

### 4.5 Messages to Java

A Java method `name(a, b, c)` is sent as `recv name: a with: b with: c`:
the first keyword is the method name and the others are labels of the
programmer's choice (a Java class file carries no parameter names). The
method is chosen by name, argument count and types, never by the labels,
so naming them after the Javadoc's parameters (`str substring: 1 to: 3`,
`map put: k value: v`) is a convention, not a check. A Doze method's full
selector is its identity (`moveTo:in:`), and for Doze methods the labels
are checked. Doze's `moveTo: String pose in: double s` is the JVM method
`moveTo(String, double)`, so Java calls it as `moveTo("open", 1.5)`; two
Doze methods of one class with the same first keyword and argument count
are an error.

A unary message to an object reads a public field when there is no method
of that name (`point x`, `Math PI`, `System out`). Fields of another object
are never assigned from outside: use its setter.

## 5. Types

- **Primitives stay primitives**, with Java's promotion (JLS 5.6) and
  string conversion (JLS 5.1.11); narrowing only through `as:`. On an
  interpreter, unboxed arithmetic is the difference between fast and slow
  loops. Primitives answer messages too: `3 toString` boxes the receiver
  and sends `toString` to the `Integer`.
- **Locals** have a declared type or their initializer's (`var`). Fields,
  parameters and results are always written, so each method is checked on
  its own.
- **Generics are used, not declared.** A member of `List<String>` is read
  from its `Signature` attribute (JVMS 4.7.9.1) with the receiver's type
  arguments substituted, and `dozec` inserts the `checkcast` erasure needs.
  `? extends T` and `? super T` read as `T`; `?` and raw types as the bound.
- **Method type parameters** (`Collections emptyList`, `stream map:`) are
  inferred by one-way unification: arguments left to right (a block's
  parameter types from what is known, its result from its body), then the
  expected type. What nothing pins down becomes its bound, and the
  programmer adds `as:`. Far less than JLS 18; section 10 records the cost.
- **Constructors** of generic classes take their type arguments from the
  expected type, as Java's `<>` does: `Map<String, Integer> m :=
  LinkedHashMap new`.
- **Literals.** `{"a", "b"}` is an `ArrayList<String>`, `{k -> v}` a
  `LinkedHashMap`, typed by the expected type, else by the join of the
  elements; with an array as expected type, an array.
- **Blocks** become the functional interface the context expects
  (section 6). Inlined blocks of 4.4 have no type of their own.
- **`nil`** is Java's null; there is no null-safety type system.

## 6. Using the Java API

- **Lookup.** `import` brings in classes from the class path (`lazyrt.jar`
  by default, then `-cp` entries); `java.lang.*` is implicit. A type name
  as a receiver sends class-side messages: static methods and fields
  (`Math max: a with: b`) and constructors (`ArrayList new`).
- **Overloads** are resolved in Java's phases, simplified (JLS 15.12.2):
  candidates by name and argument count; then applicable by subtyping and
  widening; then with boxing; then with varargs. The most specific wins; a
  tie is an error naming the candidates, fixed with `as:`. A block is
  applicable to a functional interface with its arity; between a `void`
  and a value-returning one (`submit: [...]` as `Runnable` or `Callable`),
  the block's last expression decides.
- **Functional interfaces** come from class files: one abstract method
  that is not a public `Object` method, counting inherited ones. A block
  becomes one; calling one is sending its method (`f apply: x`,
  `r run`).
- **Extending Java classes** and implementing generic interfaces
  (`implements Comparable<Point>`) emits the bridge method erasure needs,
  as javac does. An abstract method left unimplemented is an error.
- **Annotations** with constant arguments are emitted as
  `RuntimeVisibleAnnotations` when retained at run time, so `lazy.msg`'s
  `@Service` and `@Subscribe` (D8) work.
- **Exceptions** are caught with `[ ... ] on: IOException do: [:e | ...]`
  and thrown with `e signal`; `await` rethrows a failed stage's exception.

## 7. Lowering

| Doze | Class file |
|---|---|
| top level of `foo.dz` | static fields and methods and `main(String[])` of class `Foo`, or of the class `Foo` the file declares |
| block not inlined | a private synthetic `lambda$<method>$<n>` (static unless it uses `self`) and `invokedynamic` to `LambdaMetafactory.metafactory` |
| outer variable assigned after a block captured it | lives in a one-element array shared with the block |
| `ifTrue:` family, `and:`, `or:`, `whileTrue:`, `to:do:`, `timesRepeat:` | jumps; `to:do:` an `int` or `long` loop with the limit evaluated once |
| `do:` | `iterator()`/`hasNext()`/`next()`, an index loop for arrays, `entrySet()` for maps |
| `on:do:`, `ensure:` | exception table entries; the `ensure:` block copied on each exit path plus a catch-all that rethrows (no `jsr`) |
| `a = b`, `a ~= b` on references | `Objects.equals`, or `ifnull` against `nil`; primitives by value |
| `a == b` | `if_acmpeq` |
| `x is: T`, `x as: T` | `instanceof`, `checkcast` (a conversion opcode between primitives) |
| cascade | the receiver kept in a local, each message sent to it |
| `{a, b}`, `{k -> v}` | `new ArrayList(n)` / `new LinkedHashMap()` then `add`/`put` |
| `"a ${x}"`, string `+` | a `StringBuilder` `append` chain |
| `println: x` | `System.out.println` with the overload for `x`'s type |
| generic result | `checkcast` to the substituted type |
| `enum` | a `final` class extending `java.lang.Enum` with `values()` and `valueOf(String)` |

**Stack maps.** Version 52 classes need a `StackMapTable` (JVMS 4.7.4), and
LazyVM's verifier does not fall back to inference (D1,
[`design/verifier.md`](design/verifier.md)). Inlined control flow is
structured, so the generator knows the live locals and their types at
every jump target and writes a `full_frame` from its scope, with no
inference pass. The only values on the stack at a join are the results of
two-branch `ifTrue:ifFalse:`, `ifNil:ifNotNil:`, `and:` and `or:`, typed
with the expected type when there is one, else the join of the branches (nearest common superclass; interfaces join
to `Object`, which the type checker accepts, JVMS 4.10.1.2). Constructor
arguments containing a branch are evaluated into locals before `new`.
`SourceFile`, `LineNumberTable` and `LocalVariableTable` are always
written, so stack traces and JDWP (S11) point at `.dz` lines.

## 8. The compiler

Three crates: `doze-syntax` (lexer, parser, AST, diagnostics) and
`doze-check` (types, producing typed IR), with no dependencies, which game
hosts embed too ([`doze-script.md`](doze-script.md)); and `dozec`, the JVM
back end and binary, which adds `crates/classfile`. That crate already
parses class files and reads jars (with `miniz_oxide`), and its writer is
extended with `StackMapTable`, `BootstrapMethods`, `LineNumberTable` and a
stored-jar writer rather than adding a second one (shared rule 4).
Malformed source, class files and jars are diagnostics, never panics.
LazyRAD links `doze-syntax` to edit view files (section 11), so there is
one Doze parser.

| Crate: module | Job | Estimated lines |
|---|---|---|
| syntax: `lex`, `parse/`, `ast`, `diag` | tokens, interpolation, statement ends; message precedence; `file:line:col` messages | 2,300 |
| check: `types`, `universe` | subtyping, erasure, boxing, member lookup, functional interfaces, the `Universe` trait | 1,000 |
| check: `infer`, `overload`, `builtin` | sections 4.4, 5 and 6 | 1,100 |
| check: `check/`, `anf` | names, types, smart casts, captures, `async` linearisation, typed IR | 2,300 |
| dozec: `classpath`, `sig` | jars and directories, `Signature` parsing, the class-file universe | 900 |
| dozec: `gen/` | bytecode, max stack and locals, stack maps, lambdas, `async` frames | 2,500 |
| dozec: `emit`, `main` | class and jar output, the command line | 400 |

About 10,500 lines, every file under 500.

**The 2 MB budget.** The stripped static musl `dozec` stays under
2,000,000 bytes, uncompressed. `python tools/size.py --dozec` checks it like
the VM's own budget; `dozec` is a separate program, so it does not count
against D14. The levers: no dependency beyond `classfile` (whose only one,
`miniz_oxide`, the VM already carries); no `regex`, `serde` or `toml`; a
release profile for this binary with `opt-level = "s"`, LTO and one codegen
unit. If a build goes over, the costliest feature is cut or simplified; the
budget is not raised.

```bash
dozec [-cp path] [-d out | -o app.jar] [--main Class] files...
dozec run [-cp path] file.dz [args...]   # compile to a temporary directory, then exec lazyvm
dozec check files...                     # diagnostics only
```

`-o` writes a jar with a `Main-Class` manifest that `lazyvm -jar` runs. The
class path defaults to the `lazyrt.jar` next to `lazyvm`. All files of one
call are compiled together, in any order. On LazyOS, `dozec` is a static
musl program staged into the image next to `lazyvm` by the LazyOS repo.

## 9. Testing and evidence

Doze sources cannot be blessed on the oracle, but their class files run
there, which gives three independent checks:

- **Goldens.** `tests/doze/<area>/<Name>.dz` with a hand-written, reviewed
  `<Name>.out`, like `lazy.*` goldens (D9).
- **Mirrors.** Where Java can say the same thing, `<Name>.java` sits
  beside it; its `.out` is blessed on the oracle (`tools/golden.py
  --bless`) and the Doze program must print the same. This ties Doze's
  arithmetic, strings and API use to Java's without hand-editing a
  `java.*` golden.
- **Two VMs.** `tools/doze_test.py` compiles each test once and runs it on
  the oracle with `-Xverify:all` and on LazyVM; both must match the `.out`.
  The oracle's verifier checks every stack map `dozec` writes.

Also: `tests/doze/bad/*.dz` with the expected message in a `.err`; a
`cargo fuzz` target over the lexer, parser and checker, which must never
panic; and `tools/lazyos_test.py --doze`, which runs `dozec` inside LazyOS
on the same tests.

**Clean room.** `dozec` is written from the JVMS (chapters 4 and 6) and
the JLS sections cited above. `javap` of class files we compiled ourselves,
javac's output for a Java program we wrote included, is allowed (shared
rule 1) and is how a lowering is compared. Smalltalk syntax is taken from
its published descriptions; we do not read the source of javac, of a
Smalltalk implementation or of other JVM-language compilers.

## 10. Known costs and limits

- **Inference is weaker than Java's.** Long stream pipelines may end in
  `Object` and need `as:`. If mirror tests show this is common, the fix is
  a better `infer`, not new syntax.
- **Chained keyword messages need parentheses**, as in Smalltalk:
  `((xs filter: f) map: g) collect: c`.
- **Labels on Java calls are not checked**, since class files have no
  parameter names; a wrong label is only misleading, never a wrong call.
- **No generic declarations.** A library that needs `Box<T>` is written in
  Java for now; open question 3.
- **Neither Java nor Smalltalk.** Code moves to Doze by rewriting; interop
  is at the class-file level, both ways.

## 11. LazyRAD

LazyRAD (LazyOS `docs/lazyrad/`) today pairs a TOML form (`.lfm`) with a
Rhai script whose functions are found by name (`hello_button_click`). With
Doze, LazyRAD changes to fit the language: a form is ordinary Doze code,
the compiler knows nothing about LazyRAD, and there is no naming
convention, reflection or form loader at run time. A form is two files; the
designer owns the first and rewrites it on every change.

```doze
// main_form.view.dz: written by the LazyRAD designer, not by hand
import lazy.ui.*

class MainFormView {
    public final Window window := Ui window: "Hello" width: 400 height: 160
    public final Edit nameEdit := Ui edit: ""
    public final Button helloButton := Ui button: "Say hello"
    public final Label resultLabel := Ui label: ""

    new {
        window content: (Ui fixed
            place: nameEdit left: 16 top: 16 width: 240 height: 24 anchor: Anchor STRETCH_HORIZONTAL;
            place: helloButton left: 264 top: 16 width: 120 height: 28 anchor: Anchor TOP_RIGHT;
            place: resultLabel left: 16 top: 56 width: 368 height: 20 anchor: Anchor STRETCH_HORIZONTAL;
            yourself)
    }
}
```

```doze
// main_form.dz: yours
class MainForm extends MainFormView {
    private var clicks := 0

    new {
        helloButton onClick: [self greet]
        nameEdit onChange: [:text | helloButton enabled: text ~= ""]
    }

    void greet {
        clicks := clicks + 1
        resultLabel text: "Hello, ${nameEdit text}! (${clicks})"
    }
}

Ui run: MainForm new window
```

- **Events are blocks**, attached where the programmer can see them. A
  double-click on a control in the designer adds `helloButton onClick: []`
  to the constructor and puts the cursor in the block.
- **Controls are typed fields**, so completion, renames and errors come
  from the compiler (`dozec check`), not from run-time lookups.
- **The view file has a fixed shape**: one field per control initialised
  by a `Ui` factory, and one cascade of `place:` messages. The designer
  reads it back with `doze-syntax`'s parser and refuses to open a view
  that was edited outside that shape.
- **Running and packaging.** F5 is `dozec run`; Make LazyOS App packages
  the jar `dozec -o` writes, depending on `lazyvm`. The debugger is JDWP
  (S11) once LazyVM has it.
- **What `lazy.ui` needs.** A positioned layout (`Ui fixed` with
  `place(widget, left, top, width, height, anchor)` and LazyRAD's twelve
  anchors), and widgets for the LazyRAD control kinds it lacks
  (`ToggleButton`, `Hyperlink`, `MultilineEdit`, `NumberField`,
  `RadioGroup`, `GroupBox`, `Separator`, `PictureBox`; a LazyRAD `Timer` is
  `Ui every:`). That is S9 work, not compiler work.
- **What LazyRAD's Rhai library becomes.** `msg_box`, `file_read_text`,
  `random` and the rest are the Java API and `lazy.*`. Where something is
  missing it is added to `lazy.*`, not to a Doze runtime.

## 12. Open questions for the owner

1. Confirm the language and the name ("Doze", files `.dz`, compiler
   `dozec`), and the S16 stage.
2. Should `dozec` run in-process from `lazyvm` (`lazyvm hello.dz`)? That
   would make the VM larger and count against D14; the proposal keeps them
   apart and has `dozec run` exec `lazyvm`.
3. Generic declarations: worth adding after S16 if real programs need them,
   or keep "used, not declared" for good?
4. LazyRAD: confirm that new LazyRAD projects use Doze (section 11), what
   happens to existing Rhai projects (kept on `lrplay`, or converted), and
   that `Ui fixed` and the LazyRAD widgets join `lazy.ui`'s scope as S9
   items.
5. Strings stay Java's `"..."` and comments `//`, not Smalltalk's `'...'`
   and `"..."`: confirm, since agents and Java code both expect them.
