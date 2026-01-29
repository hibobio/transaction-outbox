# Batch Scheduling API Usage Examples

## Current Implementation

The current `scheduleBatch()` API works like `schedule()` but automatically detects when a Collection is passed to a method parameter that expects a single object, and expands it into multiple entries.

### How It Works

The proxy intercepts method calls and checks if any argument is a Collection. If:
- The argument IS a Collection AND
- The method parameter type is NOT a Collection
- Then it expands the Collection into multiple entries

### Example 1: Simple Batch (No Ordering)

```java
interface OrderService {
    void processOrder(Integer orderId, String context);
}

// Usage:
transactionManager.inTransaction(tx -> {
    List<Integer> orderIds = List.of(101, 102, 103, 104, 105);
    
    // This will create 5 entries: processOrder(101, "orders"), processOrder(102, "orders"), etc.
    outbox.scheduleBatch(OrderService.class).processOrder(orderIds, "orders");
});
```

**Note**: This requires the method signature to accept a single object (`Integer orderId`), but we pass a `List<Integer>`. The proxy intercepts at runtime and expands it.

### Example 2: Ordered Batch

```java
transactionManager.inTransaction(tx -> {
    List<Integer> orderIds = List.of(101, 102, 103);
    
    // All entries will be in the "orders" topic with sequential sequence numbers
    outbox.scheduleBatch(OrderService.class, "orders").processOrder(orderIds, "context");
});
```

### Example 3: Method That Accepts Collection (Not Expanded)

```java
interface BatchOrderService {
    void processOrders(Collection<Integer> orderIds, String context);  // Parameter IS a Collection
}

// Usage:
transactionManager.inTransaction(tx -> {
    List<Integer> orderIds = List.of(101, 102, 103);
    
    // This creates ONE entry (not expanded) because parameter type IS a Collection
    outbox.scheduleBatch(BatchOrderService.class).processOrders(orderIds, "context");
});
```

## The Challenge

Java's type system prevents passing a `List<Integer>` where an `Integer` is expected at compile time. The proxy intercepts at runtime, but we need to get past the compiler.

### Current Workaround

We need to use unchecked casting or the method signature must accept `Object` or `Collection`:

```java
// Option 1: Method accepts Object
interface OrderService {
    void processOrder(Object orderId, String context);  // Less type-safe
}

// Option 2: Use @SuppressWarnings with casting (not ideal)
@SuppressWarnings("unchecked")
OrderService proxy = outbox.scheduleBatch(OrderService.class);
proxy.processOrder((Integer)(Object)orderIds, "orders");  // Runtime expansion happens
```

## What You Want

```java
// From your example:
transactionManager.inTransaction(tx -> {
    outbox.scheduleBatch(MyService.class).myMethod(listOfObjects);
});

// Where myMethod signature is:
void myMethod(MyObject obj);  // Single object, not Collection
```

This is the API we're implementing, but it requires:
1. The proxy to intercept the call (✓ done)
2. Runtime detection of Collection arguments (✓ done)  
3. Expansion logic (✓ done)
4. **Getting past Java's compile-time type checking** (❌ challenge)

## Possible Solutions

1. **Accept Object in method signatures** - Less type-safe but works
2. **Use method overloading** - Have both `myMethod(MyObject)` and `myMethod(List<MyObject>)`
3. **Use @SuppressWarnings** - Not ideal but allows the pattern
4. **Create a wrapper API** - `scheduleBatchList()` that returns a different proxy type

Which approach would you prefer?
