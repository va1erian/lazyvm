# Building and running LazyVM apps in IntelliJ IDEA

LazyVM apps are ordinary Java 8 projects compiled against LazyVM's class
library (`lazyrt`) instead of the JDK's, so the editor only offers APIs
LazyVM has. Two pieces make that work:

- `dist/lazyvm-sdk`, a JDK-shaped folder (`python tools/sdk.py`):
  `bin/java(.exe)` is the LazyVM launcher, `jre/lib/rt.jar` is `lazyrt`,
  `src.zip` its sources, and `release` says Java 1.8.
- `templates/app`, a Gradle project that compiles with
  `-bootclasspath <sdk>/jre/lib/rt.jar` and runs with the SDK's launcher.

> **Status:** the Gradle build and run are checked by `tools/sdk.py` and the
> template (`gradle -p templates/app run`). The IntelliJ steps below follow
> IntelliJ's documented SDK and Gradle features but have not yet been
> verified in the IDE by the owner (plan item S3-04).

## One-time setup

1. Build the SDK from a LazyVM checkout: `python tools/sdk.py`.
2. In IntelliJ: **File > Project Structure > Platform Settings > SDKs > + >
   Add JDK from disk**, pick `dist/lazyvm-sdk`, and name it `LazyVM 1.8`.
   IntelliJ reads `release` and `jre/lib/rt.jar` like any Java 8 JDK.

## A new app

1. Copy `templates/app` somewhere and open its folder in IntelliJ
   (**File > Open**). IntelliJ imports it as a Gradle project.
2. Set `lazyvmSdk=<path to dist/lazyvm-sdk>` in the copy's
   `gradle.properties` (the template defaults to the checkout layout).
3. **Project Structure > Project > SDK**: `LazyVM 1.8`, language level 8.
   Completion and inspections now see only LazyVM's API.
4. Leave **Settings > Build, Execution, Deployment > Build Tools > Gradle >
   Build and run using** on *Gradle*: compilation then goes through the
   template's `-bootclasspath`, and the SDK needs no `javac` of its own.
   (Gradle itself runs on any JDK 8 or newer, set under *Gradle JVM*.)
5. Run: the Gradle tool window's **Tasks > other > run**, or an
   **Application** run configuration with **JRE** set to `LazyVM 1.8`.
   The launcher accepts and ignores IntelliJ's `-javaagent:...idea_rt.jar`
   and `-Dfile.encoding=...` options.

Debugging with breakpoints needs JDWP, which arrives in S11. Until then
`System.out` and stack traces are the tools; `-verbose:class` and
`-Xtrace:interp` (very slow) are available in the run configuration's VM
options.

## Without IntelliJ

```bash
gradle -p templates/app jar
gradle -p templates/app run -PappArgs="a b"
dist/lazyvm-sdk/bin/java -jar templates/app/build/libs/lazyvm-app.jar
```
