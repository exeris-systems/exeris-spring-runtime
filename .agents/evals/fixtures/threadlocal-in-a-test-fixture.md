Change under review — `exeris-spring-runtime-web/src/test/java`, a JUnit fixture.

```java
public final class RecordingClock {

    // One instant per test thread, so a parallel run cannot see another test's clock.
    private static final ThreadLocal<Instant> NOW =
            ThreadLocal.withInitial(() -> Instant.EPOCH);

    public Instant instant() {
        return NOW.get();
    }

    public static void set(Instant instant) {
        NOW.set(instant);
    }
}
```

Nothing in `src/main/java` of any runtime module references this class.
