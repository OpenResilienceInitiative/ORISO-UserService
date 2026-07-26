package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixEventIdentity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class ConsultantMessageStatServiceMariaDbReplicaIT {

  private static final String CONSULTANT_ID = "replica-proof-consultant";
  private static final String CONSULTANT_HMAC = "replica-proof-consultant-hmac";
  private static final String MATRIX_EVENT_ID = "$replica-proof:matrix.oriso";
  private static final String EVENT_HASH = MatrixEventIdentity.opaqueHash(MATRIX_EVENT_ID);

  @Autowired private ConsultantMessageStatRepository consultantMessageStatRepository;
  @Autowired private SessionRepository sessionRepository;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @AfterEach
  void deleteReplicaProofRows() {
    consultantMessageStatRepository.findAll().stream()
        .filter(stat -> EVENT_HASH.equals(stat.getSourceEventHash()))
        .forEach(consultantMessageStatRepository::delete);
  }

  @Test
  void twoServiceInstancesRecordOneStatisticForOneMatrixEvent() throws Exception {
    ConsultantIdentityHasher hasher = mock(ConsultantIdentityHasher.class);
    when(hasher.hash(CONSULTANT_ID)).thenReturn(CONSULTANT_HMAC);
    var firstInstance =
        new ConsultantMessageStatService(
            consultantMessageStatRepository, sessionRepository, hasher);
    var secondInstance =
        new ConsultantMessageStatService(
            consultantMessageStatRepository, sessionRepository, hasher);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> record(firstInstance, ready, start));
      var second = executor.submit(() -> record(secondInstance, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThat(
            consultantMessageStatRepository.findAll().stream()
                .filter(stat -> EVENT_HASH.equals(stat.getSourceEventHash())))
        .singleElement()
        .satisfies(
            stat -> {
              assertThat(stat.getConsultantHmac()).isEqualTo(CONSULTANT_HMAC);
              assertThat(stat.getSourceEventHash()).doesNotContain(MATRIX_EVENT_ID);
            });
  }

  private void record(
      ConsultantMessageStatService service, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    service.recordMessageSent(CONSULTANT_ID, null, MATRIX_EVENT_ID);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
