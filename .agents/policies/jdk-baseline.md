# Policy: JDK Baseline and Binary Neutrality

The JDK floor for `exeris-spring-runtime` is Java 25 (LTS).

## The Rule

- **Java 25 (LTS) Baseline**: `maven.compiler.release` is set to 25 across all modules.
- **Class-File Target**: Published JARs must strictly emit class-file major version **69**, minor **0**.
- **No Preview Flags**: `--enable-preview` is strictly forbidden at compile time, surefire test time, and runtime. Preview features are never allowed on the GA track.
- **Alignment with Kernel**: The compiler release level moves only in lockstep with `exeris-kernel` (ADR-066, ADR-068).
- **Class-File Tooling Floor**: Bytecode tooling (ArchUnit ASM parser, JaCoCo agent) must remain ahead of or level with bytecode class-file versions (major 69/70) so that boundary guards do not quietly skip unparsed bytecode.
- **Multi-Matrix Testing**: Builds are validated against Spring Boot 3 baseline (`matrix-sb3` profile) and forward matrix rows on Java 25+.
