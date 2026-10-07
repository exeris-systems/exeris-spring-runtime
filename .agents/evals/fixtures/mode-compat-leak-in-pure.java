// Proposed: eu/exeris/spring/runtime/web/ExerisHttpDispatcher.java
//
// Leaks compatibility package into pure mode path with ThreadLocal carrier.
package eu.exeris.spring.runtime.web;

import eu.exeris.spring.runtime.web.compat.SecurityBridge;

public final class ExerisHttpDispatcher {
    private final SecurityBridge bridge = new SecurityBridge();

    public void dispatch() {
        bridge.bindThreadLocalContext();
    }
}
