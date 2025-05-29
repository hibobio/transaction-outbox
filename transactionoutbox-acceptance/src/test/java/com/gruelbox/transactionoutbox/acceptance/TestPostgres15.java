package com.gruelbox.transactionoutbox.acceptance;

import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Submitter;
import com.gruelbox.transactionoutbox.ThreadLocalContextTransactionManager;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.testing.AbstractAcceptanceTest;
import com.gruelbox.transactionoutbox.testing.InterfaceProcessor;
import com.gruelbox.transactionoutbox.testing.LatchListener;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("WeakerAccess")
@Testcontainers
class TestPostgres15 extends AbstractAcceptanceTest {

  @Container
  @SuppressWarnings({"rawtypes", "resource"})
  private static final JdbcDatabaseContainer container =
      (JdbcDatabaseContainer)
          new PostgreSQLContainer("postgres:15")
              .withStartupTimeout(Duration.ofHours(1))
              .withReuse(true);

  @Override
  protected ConnectionDetails connectionDetails() {
    return ConnectionDetails.builder()
        .dialect(Dialect.POSTGRESQL_9)
        .driverClassName("org.postgresql.Driver")
        .url(container.getJdbcUrl())
        .user(container.getUsername())
        .password(container.getPassword())
        .build();
  }
  
  @Test
  void batchSequencing() throws Exception {
    int countPerTopic = 20;
    int topicCount = 5;

    AtomicInteger insertIndex = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(countPerTopic * topicCount);
    ThreadLocalContextTransactionManager transactionManager =
        (ThreadLocalContextTransactionManager) txManager();

    transactionManager.inTransaction(
        tx -> {
          //noinspection resource
          try (var stmt = tx.connection().createStatement()) {
            stmt.execute("DROP TABLE TEST_TABLE");
          } catch (SQLException e) {
            // ignore
          }
        });

    transactionManager.inTransaction(
        tx -> {
          //noinspection resource
          try (var stmt = tx.connection().createStatement()) {
            stmt.execute(createTestTable());
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    TransactionOutbox outbox =
        TransactionOutbox.builder()
            .transactionManager(transactionManager)
            .submitter(Submitter.withExecutor(unreliablePool))
            .attemptFrequency(Duration.ofMillis(500))
            .instantiator(
                new AbstractAcceptanceTest.RandomFailingInstantiator(
                    (foo, bar) -> {
                      transactionManager.requireTransaction(
                          tx -> {
                            //noinspection resource
                            try (var stmt =
                                tx.connection()
                                    .prepareStatement(
                                        "INSERT INTO TEST_TABLE (topic, ix, foo) VALUES(?, ?, ?)")) {
                              stmt.setString(1, bar);
                              stmt.setInt(2, insertIndex.incrementAndGet());
                              stmt.setInt(3, foo);
                              stmt.executeUpdate();
                            } catch (SQLException e) {
                              throw new RuntimeException(e);
                            }
                          });
                    }))
            .persistor(persistor())
            .listener(new LatchListener(latch))
            .initializeImmediately(false)
            .flushBatchSize(4)
            .useOrderedBatchProcessing(true)
            .build();

    outbox.initialize();
    clearOutbox();

    withRunningFlusher(
        outbox,
        () -> {
          transactionManager.inTransaction(
              () -> {
                for (int i = 1; i <= countPerTopic; i++) {
                  for (int j = 1; j <= topicCount; j++) {
                    outbox
                        .with()
                        .ordered("topic" + j)
                        .schedule(InterfaceProcessor.class)
                        .process(i, "topic" + j);
                  }
                }
              });
          assertTrue(latch.await(30, SECONDS));
        });

    var output = new HashMap<String, ArrayList<Integer>>();
    transactionManager.inTransaction(
        tx -> {
          //noinspection resource
          try (var stmt = tx.connection().createStatement();
              var rs = stmt.executeQuery("SELECT topic, foo FROM TEST_TABLE ORDER BY ix")) {
            while (rs.next()) {
              ArrayList<Integer> values =
                  output.computeIfAbsent(rs.getString(1), k -> new ArrayList<>());
              values.add(rs.getInt(2));
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });

    var indexes = IntStream.range(1, countPerTopic + 1).boxed().collect(toList());
    var expected =
        IntStream.range(1, topicCount + 1)
            .mapToObj(i -> "topic" + i)
            .collect(toMap(it -> it, it -> indexes));
    assertEquals(expected, output);
  }
}
