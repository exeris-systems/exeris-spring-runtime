Change under review — `exeris-spring-runtime-tx`, persistence/transaction.

```java
final class SpringTransactionContext {

    private static final ThreadLocal<TransactionId> CURRENT = new ThreadLocal<>();

    static void runInTransaction(TransactionId tx, Runnable work) {
        CURRENT.set(tx);
        try {
            work.run();
        } finally {
            CURRENT.remove();
        }
    }

    static TransactionId current() {
        return CURRENT.get();
    }
}
```

Called once per transaction. The transaction interceptor reads `current()` to coordinate boundaries.
