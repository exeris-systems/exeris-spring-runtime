Change under review — `exeris-spring-runtime-web`, HTTP request decode.

```java
final class ExerisHttpDecoder {

    void decodeHeaders(MemorySegment in, ExerisServerRequest request) {
        byte[] scratch = new byte[(int) in.byteSize()];
        MemorySegment.copy(in, ValueLayout.JAVA_BYTE, 0, scratch, 0, scratch.length);
        request.headers(parse(new String(scratch, StandardCharsets.ISO_8859_1)));
    }
}
```

Called once per request from the reactor thread. `in` is a slice of the connection's receive
buffer.
