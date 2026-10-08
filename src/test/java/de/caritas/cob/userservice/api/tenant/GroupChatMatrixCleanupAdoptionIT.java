package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.port.out.GroupChatMatrixCleanupTaskRepository;
import de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual repositories/row locks: a waiting cleanup must see newly committed legitimate access. */
@TestPropertySource(
    properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
class GroupChatMatrixCleanupAdoptionIT extends GroupCounsellingDpaHttpFixture {
  @Autowired GroupChatMatrixCleanupService cleanup;
  @Autowired GroupChatMatrixCleanupTaskRepository cleanupTasks;
  @Autowired PlatformTransactionManager transactions;
  private String adoptedMember;

  @Test
  @Timeout(30)
  void retrySeesLegitimateParticipantCommittedWhileItWaitedForOwnerLock() throws Exception {
    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(cleanup)).isTrue();
    var chat = storedChat(ConversationType.INTERNAL_GROUP);
    var newcomer = fixtures.consultant(OWNER, AGENCY);
    adoptedMember = newcomer.getMatrixUserId();
    var owner =
        new GroupChatMatrixCleanupService.GroupOwner(chat.getId(), consultant.getId(), OWNER);
    var id = cleanup.recordJoin(owner, newcomer.getId(), chat.getMatrixRoomId(), adoptedMember);
    var executor = Executors.newSingleThreadExecutor();
    var worker = new AtomicReference<java.util.concurrent.Future<?>>();
    var observedWaitState = new AtomicReference<List<String>>();
    try {
      Tenants.acrossAll(
          () ->
              new TransactionTemplate(transactions)
                  .executeWithoutResult(
                      status -> {
                        cleanup.lockOwner(consultant);
                        worker.set(
                            executor.submit(() -> Tenants.acrossAll(() -> cleanup.retry(id))));
                        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        do {
                          if (worker.get().isDone()) {
                            try {
                              worker.get().get();
                            } catch (Exception failure) {
                              throw new IllegalStateException(
                                  "Retry ended before reaching owner lock", failure);
                            }
                            throw new IllegalStateException(
                                "Retry returned before reaching owner lock");
                          }
                          observedWaitState.set(
                              database.queryForList(blockedRetryStateQuery(), String.class));
                          if (!observedWaitState.get().isEmpty()) break;
                          try {
                            Thread.sleep(10);
                          } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                          }
                        } while (System.nanoTime() < deadline);
                        assertThat(observedWaitState.get())
                            .as("actual retry is blocked on the owner row")
                            .isNotEmpty();
                        assertThat(worker.get().isDone()).isFalse();
                        org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.TimeoutException.class,
                            () -> worker.get().get(150, TimeUnit.MILLISECONDS));
                        participants.save(
                            GroupChatParticipant.builder()
                                .seriesId(chat.getId())
                                .chatId(chat.getId())
                                .consultantId(newcomer.getId())
                                .role(GroupChatParticipant.ParticipantRole.CO_MODERATOR)
                                .build());
                        // Keep the old failed-join intent: a later legitimate writer has adopted
                        // the same member.
                      }));
      worker.get().get(5, TimeUnit.SECONDS);
      assertThat(matrixWrites.get())
          .as("legitimate newly committed member must never be removed")
          .isZero();
      assertThat(participants.findBySeriesIdAndConsultantId(chat.getId(), newcomer.getId()))
          .isPresent();
      assertThat(cleanupTasks.findById(id))
          .as("legitimate adoption cancels stale cleanup")
          .isEmpty();
      assertObservedRetryState(observedWaitState.get());
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      cleanupTasks.deleteById(id);
    }
  }

  protected void assertObservedRetryState(List<String> observed) {
    assertThat(observed).containsExactly("REPEATABLE READ");
  }

  protected String blockedRetryStateQuery() {
    return "SELECT ISOLATION_LEVEL FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL";
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws java.io.IOException {
    String path = request.getURI().getPath();
    if (path.endsWith("/members"))
      return withSuccess("{\"members\":[\"" + adoptedMember + "\"]}", MediaType.APPLICATION_JSON)
          .createResponse(request);
    if (path.endsWith("/leave")) {
      matrixWrites.incrementAndGet();
      adoptedMember = "@synthetic-gone:synthetic.oriso.test";
      return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
