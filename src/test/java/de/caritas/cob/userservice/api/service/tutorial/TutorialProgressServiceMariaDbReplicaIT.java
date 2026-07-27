package de.caritas.cob.userservice.api.service.tutorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.UpsertTutorialProgressRequest;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.AdditionalAnswers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TutorialProgressServiceMariaDbReplicaIT {

  private static final String USER_ID = "tutorial-replica-proof";
  private static final String SURFACE = "frontend";
  private static final String TOUR_ID = "consultant-walkthrough";
  private static final int TOUR_VERSION = 1;

  @Autowired private TutorialProgressRepository tutorialProgressRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
  }

  @AfterEach
  void deleteReplicaProofRows() {
    tutorialProgressRepository.findByUserIdAndSurface(USER_ID, SURFACE).stream()
        .forEach(tutorialProgressRepository::delete);
  }

  @Test
  void concurrentFirstWritesFromTwoReplicasUpsertOneCanonicalProgressRow() throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    var logs = new ListAppender<ILoggingEvent>();
    logs.start();
    rootLogger.addAppender(logs);
    var bothReadsCompleted = new CountDownLatch(2);
    var releaseWrites = new CountDownLatch(1);
    var firstRepository = repositoryPausedAfterRead(bothReadsCompleted, releaseWrites);
    var secondRepository = repositoryPausedAfterRead(bothReadsCompleted, releaseWrites);
    var firstInstance =
        new TutorialProgressService(
            firstRepository, new TutorialProgressStore(firstRepository, transactionManager));
    var secondInstance =
        new TutorialProgressService(
            secondRepository, new TutorialProgressStore(secondRepository, transactionManager));
    var executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(() -> firstInstance.upsertOwnProgress(USER_ID, null, request("profile")));
      Future<?> second =
          executor.submit(
              () -> secondInstance.upsertOwnProgress(USER_ID, null, request("profile")));

      assertThat(bothReadsCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      releaseWrites.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      releaseWrites.countDown();
      executor.shutdownNow();
      rootLogger.detachAppender(logs);
    }

    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("Duplicate entry"));
    assertThat(tutorialProgressRepository.findByUserIdAndSurface(USER_ID, SURFACE))
        .singleElement()
        .satisfies(
            progress -> {
              assertThat(progress.getTourId()).isEqualTo(TOUR_ID);
              assertThat(progress.getTourVersion()).isEqualTo(TOUR_VERSION);
              assertThat(progress.getStatus()).isEqualTo("in_progress");
              assertThat(progress.getCurrentStepId()).isEqualTo("profile");
            });
  }

  @Test
  void existingScopeRemainsWritableWhenTheRowCapIsReached() {
    var store = new TutorialProgressStore(tutorialProgressRepository, transactionManager);
    store.upsert(progress(1, "in_progress"), 1);

    var updated = store.upsert(progress(1, "completed"), 0);

    assertThat(updated.getStatus()).isEqualTo("completed");
    assertThat(updated.getCompletedAt()).isNotNull();
    assertThat(tutorialProgressRepository.findByUserIdAndSurface(USER_ID, SURFACE)).hasSize(1);
  }

  @Test
  void newScopeIsRejectedWhenTheRowCapIsReached() {
    var store = new TutorialProgressStore(tutorialProgressRepository, transactionManager);
    store.upsert(progress(1, "in_progress"), 1);

    assertThatThrownBy(() -> store.upsert(progress(2, "in_progress"), 1))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("row limit");
    assertThat(tutorialProgressRepository.findByUserIdAndSurface(USER_ID, SURFACE)).hasSize(1);
  }

  private TutorialProgressRepository repositoryPausedAfterRead(
      CountDownLatch bothReadsCompleted, CountDownLatch releaseWrites) {
    TutorialProgressRepository adapter =
        mock(
            TutorialProgressRepository.class,
            withSettings()
                .defaultAnswer(AdditionalAnswers.delegatesTo(tutorialProgressRepository)));
    doAnswer(
            invocation -> {
              var existing =
                  tutorialProgressRepository.findByUserIdAndSurfaceAndTourIdAndTourVersion(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3));
              bothReadsCompleted.countDown();
              await(releaseWrites);
              return existing;
            })
        .when(adapter)
        .findByUserIdAndSurfaceAndTourIdAndTourVersion(USER_ID, SURFACE, TOUR_ID, TOUR_VERSION);
    return adapter;
  }

  private UpsertTutorialProgressRequest request(String currentStepId) {
    var request = new UpsertTutorialProgressRequest();
    request.setSurface(SURFACE);
    request.setTourId(TOUR_ID);
    request.setTourVersion(TOUR_VERSION);
    request.setStatus("in_progress");
    request.setCurrentStepId(currentStepId);
    return request;
  }

  private TutorialProgress progress(int version, String status) {
    var now = LocalDateTime.now();
    return TutorialProgress.builder()
        .userId(USER_ID)
        .surface(SURFACE)
        .tourId(TOUR_ID)
        .tourVersion(version)
        .status(status)
        .currentStepId("profile")
        .startedAt(now)
        .completedAt("completed".equals(status) ? now : null)
        .createDate(now)
        .updateDate(now)
        .build();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent tutorial progress proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
