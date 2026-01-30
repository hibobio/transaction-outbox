package com.gruelbox.transactionoutbox.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gruelbox.transactionoutbox.*;
import com.gruelbox.transactionoutbox.testing.AbstractAcceptanceTest;
import com.gruelbox.transactionoutbox.testing.InterfaceProcessor;
import com.gruelbox.transactionoutbox.testing.LatchListener;
import com.gruelbox.transactionoutbox.testing.ProcessedEntryListener;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("WeakerAccess")
abstract class TestOutboxCommandAddAll extends AbstractAcceptanceTest {

  @Test
  final void basicBatchAddAll() throws InterruptedException {
    int batchSize = 10;
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();
    AtomicInteger processedCount = new AtomicInteger(0);

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              processedCount.incrementAndGet();
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .build());
    }

    // Add all commands in a transaction and verify rows were created in DB
    transactionManager.inTransaction(
        tx -> {
          outbox.addAll(null, commands);
          // Verify count within the same transaction before commit
          try (PreparedStatement stmt =
              tx.connection()
                  .prepareStatement("SELECT COUNT(*) FROM TXNO_OUTBOX WHERE topic = '*'")) {
            try (ResultSet rs = stmt.executeQuery()) {
              assertTrue(rs.next());
              assertEquals(batchSize, rs.getInt(1));
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    // Verify all were processed
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertEquals(batchSize, processedCount.get());
  }

  @Test
  final void batchAddAllWithDedupeKeys() throws InterruptedException {
    int batchSize = 5;
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();
    AtomicInteger processedCount = new AtomicInteger(0);

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              processedCount.incrementAndGet();
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands with dedupe keys
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .withDedupeKey("item:" + i)
              .build());
    }

    // Add all commands in a transaction (null topic = unordered)
    transactionManager.inTransaction(() -> outbox.addAll(null, commands));

    // Verify all were processed
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertEquals(batchSize, processedCount.get());
  }

  @Test
  final void batchAddAllWithTopic() throws Exception {
    int batchSize = 5;
    String topic = "test-topic";
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();
    AtomicInteger processedCount = new AtomicInteger(0);

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              processedCount.incrementAndGet();
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .attemptFrequency(Duration.ofMillis(100))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .build());
    }

    // Add all commands in a transaction with topic
    transactionManager.inTransaction(() -> outbox.addAll(topic, commands));

    // Verify entries were created with sequential sequence numbers
    transactionManager.inTransaction(
        tx -> {
          try (PreparedStatement stmt =
              tx.connection()
                  .prepareStatement(
                      "SELECT seq FROM TXNO_OUTBOX WHERE topic = ? ORDER BY seq")) {
            stmt.setString(1, topic);
            List<Long> sequences = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
              while (rs.next()) {
                sequences.add(rs.getLong("seq"));
              }
            }
            assertEquals(batchSize, sequences.size());
            // Verify sequences are sequential starting from 1
            for (int i = 0; i < batchSize; i++) {
              assertEquals(i + 1L, sequences.get(i));
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    // Flush to process ordered entries
    withRunningFlusher(outbox, () -> assertTrue(latch.await(10, TimeUnit.SECONDS)));
    assertEquals(batchSize, processedCount.get());
  }

  @Test
  final void batchAddAllWithHeaders() throws InterruptedException {
    int batchSize = 3;
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              // Headers should be available in MDC
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands with headers
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .withHeader("source", "csv-import")
              .withHeader("batch-id", "batch-123")
              .build());
    }

    // Add all commands in a transaction (null topic = unordered)
    transactionManager.inTransaction(() -> outbox.addAll(null, commands));

    // Verify all were processed
    assertTrue(latch.await(10, TimeUnit.SECONDS));
  }

  @Test
  final void batchAddAllProcessesItemsInAdditionOrder() throws Exception {
    int batchSize = 5;
    String topic = "order-test-topic";
    TransactionManager transactionManager = txManager();
    CountDownLatch successLatch = new CountDownLatch(batchSize);
    ProcessedEntryListener processedEntryListener = new ProcessedEntryListener(successLatch);

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              // Process successfully - order will be captured by listener
                            }))
            .listener(processedEntryListener)
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .attemptFrequency(Duration.ofMillis(100))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands with distinct integer arguments in specific order
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .build());
    }

    // Add all commands in a transaction with topic (for ordered processing)
    transactionManager.inTransaction(() -> outbox.addAll(topic, commands));

    // Process the ordered entries
    withRunningFlusher(outbox, () -> assertTrue(successLatch.await(10, TimeUnit.SECONDS)));

    // Verify all were processed
    List<TransactionOutboxEntry> successfulEntries = processedEntryListener.getSuccessfulEntries();
    assertEquals(batchSize, successfulEntries.size());

    // Extract the first argument (integer) from each processed entry
    List<Integer> processedOrder = new ArrayList<>();
    for (TransactionOutboxEntry entry : successfulEntries) {
      Integer arg = (Integer) entry.getInvocation().getArgs()[0];
      processedOrder.add(arg);
    }

    // Verify that items were processed in the same order as they were added
    // (i.e., 1, 2, 3, 4, 5)
    for (int i = 0; i < batchSize; i++) {
      assertEquals(
          i + 1,
          processedOrder.get(i).intValue(),
          "Item at position " + i + " should have been processed in order");
    }
  }

  @Test
  final void batchAddAllProcessesItemsInAdditionOrderUsingProcessor() throws Exception {
    int batchSize = 7;
    String topic = "order-test-topic-processor";
    TransactionManager transactionManager = txManager();
    CountDownLatch latch = new CountDownLatch(batchSize);
    // Thread-safe list to capture processing order
    List<Integer> processedOrder = new CopyOnWriteArrayList<>();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              // Add the first argument to the list to track processing order
                              processedOrder.add(foo);
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .attemptFrequency(Duration.ofMillis(100))
            .build();

    outbox.initialize();
    clearOutbox();

    // Build list of commands with distinct integer arguments in specific order
    List<OutboxCommand> commands = new ArrayList<>();
    for (int i = 1; i <= batchSize; i++) {
      commands.add(
          OutboxCommand.call(InterfaceProcessor.class, "process", int.class, String.class)
              .withArgs(i, "value" + i)
              .build());
    }

    // Add all commands in a transaction with topic (for ordered processing)
    transactionManager.inTransaction(() -> outbox.addAll(topic, commands));

    // Process the ordered entries
    withRunningFlusher(outbox, () -> assertTrue(latch.await(10, TimeUnit.SECONDS)));

    // Verify all were processed
    assertEquals(batchSize, processedOrder.size());

    // Verify that items were processed in the same order as they were added
    // (i.e., 1, 2, 3, 4, 5, 6, 7)
    for (int i = 0; i < batchSize; i++) {
      assertEquals(
          i + 1,
          processedOrder.get(i).intValue(),
          "Item at position " + i + " should have been processed in order");
    }
  }

  @Test
  final void emptyListNoOp() {
    TransactionManager transactionManager = txManager();
    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Should not throw
    transactionManager.inTransaction(() -> outbox.addAll(null, List.of()));
    transactionManager.inTransaction(() -> outbox.addAll(null, null));
  }
}
