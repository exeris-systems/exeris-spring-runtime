# Policy: The Wall

The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) and capability tier (`exeris-caps-*`) must remain completely Spring-free.

## The Rule

- **No Spring in Kernel**: No class in `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*` may import or reference any class in `org.springframework.*`.
- **No Framework Annotations**: No Spring annotations (`@Component`, `@Service`, `@Autowired`, `@Configuration`, etc.) in kernel SPI or core touchpoints.
- **Provider Discovery**: Kernel discovery relies strictly on canonical `ServiceLoader` SPI mechanisms; Spring DI cannot replace or hijack SPI provider resolution.
- **Cap-Tier Wall**: Per High-Level Architecture §4, no `exeris-caps-*` capability module may depend on Spring internals. Cap manifests must remain reusable across kernel-direct and Spring-hosted runtimes without modifications.
- **Package Hygiene**: Package roots are inviolable: `eu.exeris.spring.*` must never intersect `eu.exeris.kernel.*`.
- **Enforcement**: Guarded by `WallIntegrityTest` (ArchUnit). Guard tests must never be weakened or bypassed.
