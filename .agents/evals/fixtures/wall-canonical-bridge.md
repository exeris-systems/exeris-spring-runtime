Change under review — `exeris-spring-runtime-web`, runtime bridge.

```java
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpExchange;

public final class CanonicalWebHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        // Dispatches through Exeris router without leaking Spring types to kernel
    }
}
```

Spring-side bridge registered via `META-INF/services/eu.exeris.kernel.spi.http.HttpHandler`. No Spring types cross into the kernel SPI.
