# Blink / Proteus runtime smoke test

This consumer project resolves Blink `1.3.13` and Proteus `1.0.13` from the
ArcartX public Maven repository without `mavenLocal()`. It builds the same
Bukkit plugin at three strengths:

- `simple`: identifier renaming only; debug metadata and literals remain.
- `medium`: longer renaming, XOR strings, normal control flow, debug removal
  and member reordering.
- `heavy`: Blink's complete built-in Proteus preset.

From the Blink repository root:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:GRADLE_USER_HOME = "$PWD\.gradle\verification-home"
.\gradlew.bat -p obfuscation-smoke obfuscate -PobfuscationLevel=simple
.\gradlew.bat -p obfuscation-smoke obfuscate -PobfuscationLevel=medium
.\gradlew.bat -p obfuscation-smoke obfuscate -PobfuscationLevel=heavy
```

Each JAR must load on Paper and return both `OBF_SMOKE_PONG` for
`obfsmoke ping` and `OBF_SMOKE_VERIFY_OK` for `obfsmoke verify`. The verification
also covers lifecycle ordering, static/dynamic events, lambda and reflected
command groups, argument/sender/tab behavior, nested/map/folder configs,
JVM object and `Runnable` contracts, Bukkit scheduling, and Nashorn execution.

With a Paper 1.20.1 runtime prepared under `runtime/paper-1.20.1`, run all
three artifacts through the same lifecycle and command assertions:

```powershell
.\obfuscation-smoke\verify-runtime.ps1
```
