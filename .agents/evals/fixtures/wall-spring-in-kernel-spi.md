Change under review — `exeris-kernel-spi`, proposed addition to HTTP SPI.

```java
package eu.exeris.kernel.spi.http;

import org.springframework.context.ApplicationContext;

public interface KernelHttpServer {

    void initialize(ApplicationContext applicationContext);
}
```

Proposed modification to pass the Spring application context directly into the kernel SPI server contract.
