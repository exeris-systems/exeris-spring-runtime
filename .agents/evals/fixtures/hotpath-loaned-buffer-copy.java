// Proposed: eu/exeris/spring/runtime/web/codec/JsonPayloadDecoder.java
//
// Incurs heap copy on pure mode hot path from LoanedBuffer to byte[].
package eu.exeris.spring.runtime.web.codec;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

public final class JsonPayloadDecoder {
    public byte[] decode(LoanedBuffer buffer) {
        byte[] copy = new byte[(int) buffer.segment().byteSize()];
        buffer.segment().copyInto(copy);
        return copy;
    }
}
