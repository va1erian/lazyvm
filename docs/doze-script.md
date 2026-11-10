# Doze as an embedded script language

Status: proposal, part of S16 in [`plan-2.md`](plan-2.md). The language is
[`doze.md`](doze.md); `async`/`await` is [`doze-async.md`](doze-async.md).
The first host is miniscent's level scripting (`E:\repos\miniscent`,
`docs/scripting.md` and `docs/scripting-language.md` there); nothing in it
changes until miniscent's owner adopts this there.

## 1. What a game host needs

Miniscent scripts levels in a small Smalltalk-style language of its own
(`crates/mscent-script`, about 3,100 lines) and chose it over Rhai and over
embedding LazyVM for reasons Doze has to respect:

| Miniscent needs | What Doze gives it |
|---|---|
| no compile step in the agent loop | the front end runs in-process at level load, in milliseconds, like today's |
| a script really suspends (`wait:` parks one handler) | `async` blocks and `await`, lowered to the host VM's own task parking |
| typos fail at load, even in handlers that never fire | static types: an unknown method, a call on the wrong kind of handle and an unknown level name in a literal are load errors |
| determinism and replays | the language has no clock, threads or hash-ordered iteration; budgets stay the host VM's |
| untrusted input, never a panic | the front end's limits and fuzzing (section 7) |
| small, no dependencies | the front-end crates have none (section 8) |
| `Game` stays `Clone` | the host VM is unchanged in kind: miniscent keeps its heap, frames and scheduler |

So Doze replaces miniscent's syntax, parser and checker, not its VM: the
same front end feeds two back ends, `dozec`'s class files for LazyVM and a
host's own bytecode.

## 2. One front end, two back ends

The compiler is split into crates with one job each (doze.md section 8):

| Crate | Job | Depends on |
|---|---|---|
| `doze-syntax` | lexer, parser, AST, diagnostics | nothing |
| `doze-check` | names, types, inference, blocks, `async` linearisation; produces typed IR | `doze-syntax` |
| `dozec` | the JVM back end: class path, class files, jars | the two above and `classfile` |

`doze-check` sees types only through a `Universe` trait: look up a type, its
members, its single abstract method, whether a type is awaitable, and check
a name literal. `dozec` implements it from class files; a host implements
it from interface files (section 4). The typed IR it hands a back end has
calls already resolved, captured variables marked, and every `await` lifted
to statement level, so a host back end is a walk over a small tree.

The crates live in this repo under `crates/` and are licensed MIT OR
Apache-2.0 like the rest of the workspace; miniscent pins them by git
revision. Moving them to a repository of their own, if a third host
appears, is open question 1.

## 3. The script profile

A host compiles with `Profile::Script`, a fixed subset of Doze. Using
anything outside it is a load error saying "not available in scripts".

| In | Out |
|---|---|
| top-level statements, `var`s and methods; several files share one namespace | `package`, `import`; declaring `class`, `interface`, `enum` (except in interface files) |
| `boolean`, `int` (32-bit, wrapping), `double` (IEEE 754), `String`, `List<T>`, handle types from the host | `long`, `float`, `byte`, `short`, `char`, arrays, maps |
| the built-in messages of doze.md 4.4: `ifTrue:`, `whileTrue:`, `to:do:`, `do:`, `ifNil:`, `^` | `on:do:`, `ensure:`, `signal`: an error ends the task, as miniscent fails closed |
| blocks, capturing and changing outer variables; cascades | annotations (except name checks in interface files) |
| `async`, `await`, string interpolation, `=` by value, `is:`/`as:` on handle types | generics beyond `List<T>`; anything reached through Java class files |

Semantics are Doze's, which are Java's for arithmetic and strings: the
host's VM implements them, not its old rules. For miniscent that means
`2 + 3 * 4` is 14, lists are 0-indexed, numbers are `double` rather than
`f32` (converted at the native boundary, still checked for being finite),
and a script has `int` too.

## 4. Interface files

A host describes its API in Doze syntax, in a `.dzi` file the checker reads
like any source: interfaces for handle types and callbacks, and functions
without bodies. It is also the API reference agents read, so miniscent's
`--script-api` prints it.

```doze
// miniscent.dzi (abridged)
interface Vec {}
interface Handler { void run }
interface PlayerHandler { void run: Player p }
interface EnemyHandler { void run: Enemy e }
interface Condition { boolean test }

interface Mover {
    void pose: String pose move: Vec offset
    async void moveTo: String pose in: double seconds   // await it to wait for arrival
    boolean moving
    void onArrived: Handler h
}
interface Zone {
    void onEnter: PlayerHandler h
    void onceEnter: PlayerHandler h
    void onExit: PlayerHandler h
}
interface Group {
    int alive
    void onEachDestroyed: EnemyHandler h
    void onAllDestroyed: Handler h
}
interface Enemy {}
interface Player {
    int hp
    void heal: int n
    void give: String key
    boolean has: String key
}

Mover mover: @Name("mover") String name
Zone zone: @Name("zone") String name
List<Vec> markers: @Name("marker") String tagOrPrefix
Group tagged: String tag
Player player
Vec x: double x y: double y z: double z
void spawn: String kind at: Vec at tag: String tag
void spawnAll: String kind at: List<Vec> at tag: String tag
void say: String text
async void wait: double seconds
async void waitDestroyed: Group g
void fork: Handler h
```

- **Natives bind by name.** The host gives one Rust function per declared
  function and method, by full selector (`"Mover>>moveTo:in:"`); a declared one with no binding,
  or a binding with no declaration, fails at start-up, and a unit test
  keeps the two lists equal. The declaration is the single source of
  argument types, so the natives no longer check them.
- **Name literals are checked at load.** A parameter annotated
  `@Name("mover")` makes the checker pass every string literal given to it
  to `Universe::check_name`, which asks the level and returns the closest
  match for the message. A name built at run time is checked when the call
  runs, as today.
- **`async` natives** return to the host VM "pending" with a wake
  condition; the VM parks the task and resumes it with the result. Called
  without `await`, the native acts and the script goes on (a door starts
  moving; the script does not wait for it).

## 5. Async in a host

The rules of [`doze-async.md`](doze-async.md) section 2 hold, with the host
in place of executors: an `async` block given to a handler runs as its own
task, `await` parks that task until the host wakes it, and a failure ends
the task with file and line. There is no `CompletionStage`: an `async` call
without `await` gives a task handle that can only be awaited later, and its
type has no name in scripts (`var t := waitDestroyed: g` then `await t`).
Cancellation is the host's: miniscent ends a task when its handler is
disabled.

## 6. Miniscent in Doze

The examples of miniscent's `docs/scripting.md`, written against the
interface above. Doze's message syntax is close to what miniscent scripts
already look like; the visible changes are `var` on declarations,
`"..."` strings, a `.` at the end of a line becoming optional, Java's
operator precedence, and `async`/`await` where a handler waits (`wait: 1`
becomes `await wait: 1`).

```doze
var vault := mover: "vault_door"
vault pose: "open" move: (x: 0 y: 0 z: 4)

(markers: "vault_switch") do: [:m | spawn: "switch" at: m tag: "vault_switch"]

var entry := mover: "hall_entry"
var wayOut := mover: "hall_exit"
var switches := tagged: "vault_switch"
switches onEachDestroyed: [:e | say: "${switches alive} left"]
switches onAllDestroyed: [
    vault moveTo: "open" in: 1.5
    say: "Vault unlocked"
]

(zone: "hall") onceEnter: async [:p |
    entry moveTo: "closed" in: 0.5
    await wait: 1
    spawnAll: "drone" at: (markers: "hall_wave1") tag: "wave1"
    await waitDestroyed: (tagged: "wave1")
    p hp < 50 ifTrue: [p heal: 50]
    wayOut moveTo: "open" in: 1.5
]
```

Library code is plain script, and other files call it without a receiver:

```doze
// assets/scripts/lib/doors.dz
void autoDoor: String name travel: Vec travel key: String key {
    var door := mover: name
    door pose: "open" move: travel
    var area := zone: name + "_area"
    area onEnter: [:p |
        (key isNil or: [p has: key])
            ifTrue: [door moveTo: "open" in: 0.6]
            ifFalse: [say: "Needs the ${key} keycard"]
    ]
    area onExit: [:p | door moveTo: "closed" in: 0.8]
}
```

Miniscent's prelude (`waitUntil:`, `every:do:`, `after:do:`) becomes Doze
too: `async void waitUntil: Condition c { [c test] whileFalse: [await wait: 0] }`.

## 7. Untrusted input

The front end takes the host's limits as parameters (file size, nesting
depth, identifiers, constants) and reports every problem as a diagnostic
with file, line and closest match, never a panic and never unbounded
recursion: the parser counts its depth and stops at the limit. Its
`cargo fuzz` target runs `doze-syntax` and `doze-check` against a fixed
interface file. Run-time limits (operations, heap, tasks) stay the host
VM's.

## 8. Size

`doze-syntax` and `doze-check` are estimated at 6,700 of the 10,500
lines; the class-file universe and the code generator stay in `dozec`. In
a host built with `opt-level = "s"` and LTO that is an estimated 250 to
350 KB, against the 172 KB miniscent's whole script crate adds today and
its game at 1.22 MB. That is a real cost for miniscent and is measured,
not assumed, before it decides (S16-08's check). Cargo features keep the
JVM-only parts (generic method inference, overload phases) out of a script
host if the measurement says they matter.

## 9. Evidence

- **Conformance.** `tests/doze/script/` holds programs that stay inside
  the script profile and use only `println`. Their `.out` comes from
  `dozec` on LazyVM; miniscent runs the same files through its VM with a
  stub host and must print the same, so the two back ends agree on the
  language.
- **Profile errors.** `tests/doze/script/bad/` checks that each excluded
  feature is refused with its message.
- **Interface files.** A unit test checks a `.dzi` with a stub universe:
  name literals, unknown members, a wrong handle kind and an un-annotated
  string parameter.

## 10. Open questions for the owner

1. Do the front-end crates stay in this repo, pinned by miniscent, or move
   to a repository of their own now?
2. Does miniscent adopt Doze in place of its Smalltalk syntax? That
   reverses the decision in its `docs/scripting-language.md` and is a
   miniscent work item, measured there against its size budget.
3. Should the script profile include maps (`[k: v]`, insertion-ordered),
   which miniscent has never needed?
