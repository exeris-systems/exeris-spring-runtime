// Proposed: eu/exeris/spring/runtime/web/codec/JsonPayloadDecoder.java
//
// Zero-copy decode operating directly on MemorySegment slice.
package eu.exeris.spring.runtime.web.codec;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import java.lang.foreign.MemorySegment;

public final class JsonPayloadDecoder {
    public MemorySegment decode(LoanedBuffer buffer) {
        return buffer.segment();
    }
}
