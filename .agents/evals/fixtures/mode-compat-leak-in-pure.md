Change under review — `exeris-spring-runtime-web`, pure request path.

```java
package eu.exeris.spring.runtime.web;

import eu.exeris.spring.runtime.web.compat.ServletBridgeAdapter;

public final class PureHttpDispatcher {

    private final ServletBridgeAdapter servletBridge = new ServletBridgeAdapter();

    public void dispatch(MemorySegment request) {
        // Pure path invoking compatibility servlet bridge
        servletBridge.handle(request);
    }
}
```

Pure mode dispatcher directly importing and delegating to a compatibility module adapter on the request path.
