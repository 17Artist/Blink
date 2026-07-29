# Blink / Proteus obfuscation acceptance

Date: 2026-07-28

## Resolved versions

- Blink Gradle plugin: `1.3.13`
- Proteus Gradle plugin: `1.0.13`
- Runtime: Paper `git-Paper-196` for Minecraft `1.20.1`
- Runtime JDK: Oracle JDK `17.0.7`

Proteus `1.0.13` was published to the ArcartX release repository. The final
consumer build resolved it from the public Maven group with both
`mavenLocal()` entries removed and a clean Gradle dependency cache.

## Runtime assertions

All three artifacts completed the same sequence:

1. Kotlin was cold-downloaded for the first artifact, reused from Blink's
   runtime cache for the remaining artifacts, and bootstrapped before Blink
   Kotlin code ran.
2. Nashorn and its isolated ASM dependencies followed the same cold/cache
   path; `1 + 2 + 3` evaluated to `6`.
3. `@Awake` completed `LOAD`, priority-ordered `ENABLE` (`-10`, `0`, `10`),
   `ACTIVE`, and `DISABLE`.
4. Both `@AutoListener` and `EventManager.listen` observed console command
   events; dynamic listener lookup and removal succeeded.
5. Lambda commands, the `os` alias, required/optional argument parsing,
   `SenderType.CONSOLE`, annotation-discovered `BlinkCommandGroup`, and custom
   tab completion all succeeded.
6. `BlinkConfig` loaded implicit and explicit keys, a nested `BlinkSection`,
   `Map<String, BlinkSection>`, and an ignored field. Saving preserved comments
   and omitted `@Ignore`. `BlinkConfigFolder` loaded `entries/sub/alpha.yml`.
7. Kotlin data-class `equals` / `hashCode` / `toString`, `HashSet` lookup,
   direct `Runnable.run`, and Bukkit-scheduled `Runnable` retained JVM semantics.
8. `obfsmoke verify` returned `OBF_SMOKE_VERIFY_OK
   digest=d3b6842440ba`; Paper exited with code `0`.

## Final artifacts

| Strength | Size | SHA-256 |
| --- | ---: | --- |
| simple | 153,439 bytes | `E1BF59993E68BD5F61BF2ED4D3A626AD0F14C82BC931300938884EF4FA931D59` |
| medium | 160,672 bytes | `E7907AD2ECB8152EA53BC37C3F429C1EC2F86103F0EFF13C41D4C2730FAC67B4` |
| heavy | 167,623 bytes | `A189CE69139DD873D83CD879C9ED5955ECAA580FCC546675C9A3C601BE7CAB04` |

Each JAR contains 70 classes and keeps
`com.example.blinkobfsmoke.BlinkGeneratedMain` as its `plugin.yml` entrypoint.

## Static checks

- Simple keeps the probe literals and `SmokePlugin.kt`, as intended.
- Medium and heavy contain none of the probe secret, command success/failure
  strings, or string-concat recipes in processed classes.
- `javap` confirms that the medium and heavy `SmokeCore` classes each contain
  four runtime opaque-predicate probes; medium uses the XOR decryptor and heavy
  uses the AES decryptor.
- The only remaining `SmokePlugin.kt` reference in medium/heavy is
  `SmokeSettings.class`, which Blink deliberately excludes to preserve
  reflection-based YAML field names.
- The heavy `SmokeRuntime` class has no `SourceFile`, line-number table,
  Kotlin source-debug extension, or original source filename.
- No mapping file renames `Function1.invoke(Object)` bridge methods.
- No mapping file renames the tested `Object` or `Runnable` contract methods.

## Acceptance boundary

This is now a broad Blink core regression, not proof of every external
integration. It does not claim:

- player-client behavior such as player-only commands or live tab UI;
- Aria shared-host election, Asteroid host/NMS packet behavior, or two-plugin
  shared-host teardown;
- every third-party reflection/serialization framework. Such DTOs still need
  explicit `obfuscateExclude`, stable annotations, or keep rules.

Run the complete runtime check again with:

```powershell
.\obfuscation-smoke\verify-runtime.ps1
```
