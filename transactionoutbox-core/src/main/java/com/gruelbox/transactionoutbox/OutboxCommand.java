package com.gruelbox.transactionoutbox;

import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * A command object representing a method invocation to be scheduled via {@link
 * TransactionOutbox#addAll(java.util.List)}.
 *
 * <p>This allows building a list of commands explicitly and persisting them in a single batch insert
 * operation, avoiding proxy/reflection at the call site. Useful for bulk operations like CSV imports
 * or batch processing.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * List<OutboxCommand> commands = items.stream()
 *     .map(item -> OutboxCommand.call(MyClass.class, "myMethod", String.class, Integer.class)
 *         .withArgs(item.getA(), item.getB())
 *         .withDedupeKey("item:" + item.getId())
 *         .withHeader("source", "csv-import")
 *         .build())
 *     .collect(Collectors.toList());
 *
 * transactionManager.inTransaction(() -> {
 *     outbox.addAll("my-topic", commands);
 * });
 * }</pre>
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@ToString
public class OutboxCommand {

  private final Class<?> targetClass;
  private final String methodName;
  private final Class<?>[] parameterTypes;
  private final Object[] args;
  private final String uniqueRequestId;
  private final Map<String, String> mdc;

  /**
   * Creates a new builder for an {@link OutboxCommand}.
   *
   * @param targetClass The target class to invoke the method on.
   * @param methodName The method name to invoke.
   * @param parameterTypes The parameter types of the method. Required because they cannot be reliably
   *     derived from args (null values, primitives).
   * @return A new builder.
   */
  public static Builder call(Class<?> targetClass, String methodName, Class<?>... parameterTypes) {
    return new Builder(targetClass, methodName, parameterTypes);
  }

  /** Builder for {@link OutboxCommand}. */
  public static class Builder {
    private final Class<?> targetClass;
    private final String methodName;
    private final Class<?>[] parameterTypes;
    private Object[] args;
    private String uniqueRequestId;
    private Map<String, String> mdc;

    private Builder(Class<?> targetClass, String methodName, Class<?>[] parameterTypes) {
      this.targetClass = targetClass;
      this.methodName = methodName;
      this.parameterTypes = parameterTypes;
    }

    /**
     * Sets the arguments for the method invocation. Must match the parameter types provided in {@link
     * #call(Class, String, Class[])}.
     *
     * @param args The method arguments.
     * @return This builder.
     */
    public Builder withArgs(Object... args) {
      this.args = args;
      return this;
    }

    /**
     * Sets a unique request ID (deduplication key) for this command. If set, the command will be
     * saved individually rather than as part of the batch insert.
     *
     * @param uniqueRequestId The unique request ID. May be up to 250 characters.
     * @return This builder.
     */
    public Builder withDedupeKey(String uniqueRequestId) {
      this.uniqueRequestId = uniqueRequestId;
      return this;
    }

    /**
     * Adds a header (MDC context) entry. Headers are restored when the command is executed, useful
     * for tracing or context propagation.
     *
     * @param key The header key.
     * @param value The header value.
     * @return This builder.
     */
    public Builder withHeader(String key, String value) {
      if (this.mdc == null) {
        this.mdc = new HashMap<>();
      }
      this.mdc.put(key, value);
      return this;
    }

    /**
     * Sets the MDC (Mapped Diagnostic Context) map. This will be restored when the command is
     * executed. Overrides any headers set via {@link #withHeader(String, String)}.
     *
     * @param mdc The MDC map.
     * @return This builder.
     */
    public Builder withMdc(Map<String, String> mdc) {
      this.mdc = mdc != null ? new HashMap<>(mdc) : null;
      return this;
    }

    /**
     * Builds the {@link OutboxCommand}.
     *
     * @return The built command.
     */
    public OutboxCommand build() {
      if (args == null) {
        args = new Object[0];
      }
      if (args.length != parameterTypes.length) {
        throw new IllegalArgumentException(
            String.format(
                "Argument count mismatch: expected %d arguments (for %d parameter types), got %d",
                parameterTypes.length, parameterTypes.length, args.length));
      }
      return new OutboxCommand(
          targetClass, methodName, parameterTypes, args, uniqueRequestId, mdc);
    }
  }
}
