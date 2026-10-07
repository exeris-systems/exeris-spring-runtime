# Reference: Build and Testing

## Baseline and Environment

- **JDK Baseline**: Java 25 (LTS), class-file version **69.0**.
- **No Preview Flags**: `--enable-preview` is strictly forbidden at compile time, surefire test time, and runtime.
- **VM Options**: Tested under `-XX:+UnlockExperimentalVMOptions -XX:+UseZGC`.

## Maven Commands

```bash
# Full reactor build with GitHub Packages settings
mvn -s .github/maven-settings.xml clean install

# Run single module tests
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web -am test

# Run specific test class
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web test -Dtest=ExerisHttpDispatcherTest

# Run specific test method
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web test -Dtest=ExerisHttpDispatcherTest#methodName

# Spring Boot 3 matrix profile
mvn -s .github/maven-settings.xml -Pmatrix-sb3 test
```

## Settings & Credentials

The `.github/maven-settings.xml` file configures repository mirrors and snapshots:
- `PACKAGES_READ_TOKEN`: Personal Access Token (classic) with `read:packages` to pull `exeris-kernel` snapshots.
- `GITHUB_TOKEN`: Actions token or developer token for `exeris-spring-runtime` packages.

## Four-Layer Testing Model

1. **Unit**: Codec mapping, route evaluation, config validation.
2. **Module Integration**: Spring bean wiring and SPI adapter coordination.
3. **Runtime Integration**: End-to-end host runtime tests where Spring context starts on top of the Exeris kernel engine.
4. **Architecture Guards**: ArchUnit tests that must remain green:
   - `WallIntegrityTest`: Enforces zero Spring types in kernel boundaries.
   - `ModuleBoundaryTest`: Verifies banned dependency edges.
   - `PureModeClasspathGuardTest`: Confirms banned servlet/reactive libraries are absent from pure-mode runtime.
   - `CompatibilityIsolationGuardTest`: Asserts pure code does not import from `*.compat.*`.
