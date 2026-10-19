package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupChatJoinRequestLockIT {

  @Autowired private GroupChatJoinRequestRepository repository;
  @Autowired private TransactionTemplate transactions;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void secondDecisionReadsCommittedStatusAfterFirstDecisionReleasesLock() throws Exception {
    var request =
        repository.save(
            GroupChatJoinRequest.builder()
                .seriesId(101L)
                .consultantId("consultant-1")
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    var firstHasLock = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondIsReading = new CountDownLatch(1);
    var secondCompletedRead = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      ignored -> {
                        var locked = repository.findByIdForUpdate(request.getId()).orElseThrow();
                        locked.setStatus(Status.ADMITTED);
                        firstHasLock.countDown();
                        try {
                          assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        repository.save(locked);
                      }));
      assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();

      var second =
          executor.submit(
              () ->
                  transactions.execute(
                      ignored -> {
                        secondIsReading.countDown();
                        var status =
                            repository.findByIdForUpdate(request.getId()).orElseThrow().getStatus();
                        secondCompletedRead.countDown();
                        return status;
                      }));
      assertThat(secondIsReading.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(secondCompletedRead.await(250, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        releaseFirst.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Status.ADMITTED);
    }
  }
}
