// Proposed: eu/exeris/spring/runtime/web/compat/SecurityBridge.java
//
// Properly isolated compatibility bridge with @CompatibilityMode and finally cleanup.
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.spring.runtime.web.CompatibilityMode;

@CompatibilityMode
public final class SecurityBridge {
    private static final ThreadLocal<String> CTX = new ThreadLocal<>();

    public void runWithContext(Runnable action) {
        CTX.set("user");
        try {
            action.run();
        } finally {
            CTX.remove();
        }
    }
}
