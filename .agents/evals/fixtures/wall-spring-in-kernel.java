// Proposed: eu/exeris/kernel/spi/http/SpringAwareHttpHandler.java
//
// Smuggles Spring ApplicationContext into kernel SPI touchpoint.
package eu.exeris.kernel.spi.http;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public interface SpringAwareHttpHandler extends HttpHandler {
    ApplicationContext getContext();
}
