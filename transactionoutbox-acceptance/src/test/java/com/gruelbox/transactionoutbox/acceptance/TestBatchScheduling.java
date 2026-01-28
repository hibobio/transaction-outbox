package com.gruelbox.transactionoutbox.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.gruelbox.transactionoutbox.*;
import com.gruelbox.transactionoutbox.testing.AbstractAcceptanceTest;
import com.gruelbox.transactionoutbox.testing.InterfaceProcessor;
import com.gruelbox.transactionoutbox.testing.LatchListener;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("WeakerAccess")
class TestBatchScheduling extends AbstractAcceptanceTest {

  @Test
  final void basicBatchSchedulingWithoutOrdering() throws InterruptedException {
    int batchSize = 10;
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();
    List<Integer> processedValues = new ArrayList<>();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(
                Instantiator.using(
                    clazz ->
                        (InterfaceProcessor)
                            (foo, bar) -> {
                              synchronized (processedValues) {
                                processedValues.add(foo);
                              }
                            }))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    transactionManager.inTransaction(
        () -> {
          outbox
              .scheduleBatch(InterfaceProcessor.class, batchSize)
              .execute((proxy, index) -> proxy.process(index + 1, "batch-" + (index + 1)));
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertEquals(batchSize, processedValues.size());
    // Verify all values were processed (order may vary)
    for (int i = 1; i <= batchSize; i++) {
      assertTrue(processedValues.contains(i), "Missing value: " + i);
    }
  }

  @Test
  final void orderedBatchSchedulingWithSequenceVerification() throws Exception {
    int batchSize = 5;
    String topic = "test-topic";
    CountDownLatch latch = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .attemptFrequency(Duration.ofMillis(100))
            .build();

    outbox.initialize();
    clearOutbox();

    // Schedule batch with ordering
    transactionManager.inTransaction(
        () -> {
          outbox
              .scheduleBatch(InterfaceProcessor.class, batchSize)
              .ordered(topic)
              .execute((proxy, index) -> proxy.process(index + 1, "value-" + (index + 1)));
        });

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
              assertEquals(i + 1L, sequences.get(i), "Sequence mismatch at index " + i);
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    // Process the entries
    withRunningFlusher(outbox, () -> assertTrue(latch.await(10, TimeUnit.SECONDS)));
  }

  @Test
  final void batchSchedulingWithMultipleTopics() throws Exception {
    int batchSize = 3;
    String topic1 = "topic-1";
    String topic2 = "topic-2";
    CountDownLatch latch1 = new CountDownLatch(batchSize);
    CountDownLatch latch2 = new CountDownLatch(batchSize);
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .listener(
                new TransactionOutboxListener() {
                  @Override
                  public void success(TransactionOutboxEntry entry) {
                    if (topic1.equals(entry.getTopic())) {
                      latch1.countDown();
                    } else if (topic2.equals(entry.getTopic())) {
                      latch2.countDown();
                    }
                  }
                })
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .attemptFrequency(Duration.ofMillis(100))
            .build();

    outbox.initialize();
    clearOutbox();

    // Schedule two batches with different topics
    transactionManager.inTransaction(
        () -> {
          outbox
              .scheduleBatch(InterfaceProcessor.class, batchSize)
              .ordered(topic1)
              .execute((proxy, index) -> proxy.process(index + 1, "topic1-value"));
        });

    transactionManager.inTransaction(
        () -> {
          outbox
              .scheduleBatch(InterfaceProcessor.class, batchSize)
              .ordered(topic2)
              .execute((proxy, index) -> proxy.process(index + 1, "topic2-value"));
        });

    // Verify sequences are independent per topic
    transactionManager.inTransaction(
        tx -> {
          try (PreparedStatement stmt =
              tx.connection()
                  .prepareStatement(
                      "SELECT topic, seq FROM TXNO_OUTBOX WHERE topic IN (?, ?) ORDER BY topic, seq")) {
            stmt.setString(1, topic1);
            stmt.setString(2, topic2);
            try (ResultSet rs = stmt.executeQuery()) {
              int count = 0;
              String currentTopic = null;
              long expectedSeq = 1;
              while (rs.next()) {
                String topic = rs.getString("topic");
                long seq = rs.getLong("seq");
                if (!topic.equals(currentTopic)) {
                  currentTopic = topic;
                  expectedSeq = 1;
                }
                assertEquals(expectedSeq, seq, "Sequence mismatch for topic " + topic);
                expectedSeq++;
                count++;
              }
              assertEquals(batchSize * 2, count);
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    withRunningFlusher(
        outbox,
        () -> {
          assertTrue(latch1.await(10, TimeUnit.SECONDS));
          assertTrue(latch2.await(10, TimeUnit.SECONDS));
        });
  }

  @Test
  final void batchSchedulingTransactionRollback() throws Exception {
    int batchSize = 5;
    TransactionManager transactionManager = txManager();
    CountDownLatch latch = new CountDownLatch(batchSize);

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .listener(new LatchListener(latch))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Schedule batch but rollback transaction
    try {
      transactionManager.inTransactionThrows(
          tx -> {
            outbox
                .scheduleBatch(InterfaceProcessor.class, batchSize)
                .execute((proxy, index) -> proxy.process(index + 1, "value"));
            throw new RuntimeException("Intentional rollback");
          });
      fail("Expected exception");
    } catch (RuntimeException e) {
      assertEquals("Intentional rollback", e.getMessage());
    }

    // Verify no entries were created (transaction rolled back)
    transactionManager.inTransaction(
        tx -> {
          try (PreparedStatement stmt =
              tx.connection().prepareStatement("SELECT COUNT(*) FROM TXNO_OUTBOX")) {
            try (ResultSet rs = stmt.executeQuery()) {
              assertTrue(rs.next());
              assertEquals(0, rs.getInt(1), "Entries should not exist after rollback");
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    // Verify nothing was processed
    assertFalse(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  final void batchSchedulingEmptyBatch() {
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();
    clearOutbox();

    // Should not throw exception for empty batch
    assertDoesNotThrow(
        () ->
            transactionManager.inTransaction(
                () -> {
                  outbox
                      .scheduleBatch(InterfaceProcessor.class, 0)
                      .execute((proxy, index) -> proxy.process(index, "value"));
                }));
  }

  @Test
  final void batchSchedulingInvalidCount() {
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();

    assertThrows(
        IllegalArgumentException.class,
        () -> outbox.scheduleBatch(InterfaceProcessor.class, -1));
  }

  @Test
  final void batchSchedulingErrorHandling() throws Exception {
    int batchSize = 3;
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

    // Schedule batch successfully
    transactionManager.inTransaction(
        () -> {
          outbox
              .scheduleBatch(InterfaceProcessor.class, batchSize)
              .execute((proxy, index) -> proxy.process(index + 1, "value"));
        });

    // Verify entries were created
    transactionManager.inTransaction(
        tx -> {
          try (PreparedStatement stmt =
              tx.connection().prepareStatement("SELECT COUNT(*) FROM TXNO_OUTBOX")) {
            try (ResultSet rs = stmt.executeQuery()) {
              assertTrue(rs.next());
              assertEquals(batchSize, rs.getInt(1));
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    // Process entries
    withRunningFlusher(
        outbox,
        () -> {
          assertTrue(latch.await(10, TimeUnit.SECONDS));
          assertEquals(batchSize, processedCount.get());
        });
  }

  @Test
  final void batchSchedulingNullInvoker() {
    TransactionManager transactionManager = txManager();

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .instantiator(Instantiator.using(clazz -> (InterfaceProcessor) (foo, bar) -> {}))
            .persistor(Persistor.forDialect(connectionDetails().dialect()))
            .build();

    outbox.initialize();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactionManager.inTransaction(
                () ->
                    outbox
                        .scheduleBatch(InterfaceProcessor.class, 5)
                        .execute(null)));
  }
}
