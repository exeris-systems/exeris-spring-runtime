Change under review — `exeris-spring-runtime-web/src/main/java/eu/exeris/spring/runtime/web/compat`, compatibility bridge.

```java
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.spring.runtime.web.CompatibilityMode;

@CompatibilityMode
public final class IsolatedServletBridge {

    public void handle(Object servletRequest) {
        // Isolated compatibility path, marked with @CompatibilityMode
    }
}
```

Isolated compatibility adapter located in `*.compat.*` and marked with `@CompatibilityMode`. Never referenced by pure-mode code.
