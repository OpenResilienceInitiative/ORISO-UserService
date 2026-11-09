package de.caritas.cob.userservice.api.port.out;

import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.RandomStringUtils;
import org.hibernate.Hibernate;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ChatRepositoryIT {

  private static final EasyRandom easyRandom = new EasyRandom();

  @Autowired private ChatRepository underTest;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private ChatOccurrenceExceptionRepository occurrenceExceptions;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private EntityManager entityManager;

  private Consultant consultant;

  private Chat chat;

  @AfterEach
  public void restore() {
    if (nonNull(chat)) {
      underTest.deleteById(chat.getId());
      chat = null;
      consultant = null;
    }
  }

  @Test
  @Sql(value = "/database/chatAndRelationData.sql")
  void findAssignedByUserId_Should_FindAllDirectAssignedChats() {
    String userId = "015d013d-95e7-4e91-85b5-12cdb3d317f3";

    var assignedChats = underTest.findAssignedByUserId(userId);

    assertEquals(2, assignedChats.size());
    assertEquals(0, assignedChats.get(0).getId());
    assertEquals(1, assignedChats.get(1).getId());
  }

  @Test
  @Sql(value = "/database/chatAndRelationData.sql")
  void findByUserId_Should_FindAllChatWithChatAgencyRelation() {
    String userId = "017cac2a-2086-47eb-9f8e-40547dfa2fd5";

    var chats = underTest.findByUserId(userId);

    assertEquals(1, chats.size());
    assertEquals(2, chats.get(0).getId());
  }

  @Test
  @Sql(value = "/database/chatAndRelationData.sql")
  void findByAgencyIds_Should_FetchChatAgencies() {
    var chats = underTest.findByAgencyIds(Set.of(1731L));

    assertEquals(2, chats.size());
    assertTrue(Hibernate.isInitialized(chats.get(0).getChatAgencies()));
    assertTrue(Hibernate.isInitialized(chats.get(1).getChatAgencies()));
  }

  @Test
  @Sql(value = "/database/chatAndRelationData.sql")
  void findByMatrixRoomIds_Should_FetchChatAgencies() {
    var chats = underTest.findByMatrixRoomIdIn(Set.of("!chat-0:matrix.example"));

    assertEquals(1, chats.size());
    assertEquals(0, chats.get(0).getId());
    assertTrue(Hibernate.isInitialized(chats.get(0).getChatAgencies()));
    assertEquals(2, chats.get(0).getChatAgencies().size());
  }

  @Test
  @Sql(value = "/database/chatAndRelationData.sql")
  void findByIdsWithChatAgencies_Should_FetchChatAgencies() {
    var chats = underTest.findByIdsWithChatAgencies(Set.of(0L));

    assertEquals(1, chats.size());
    assertEquals(0, chats.get(0).getId());
    assertTrue(Hibernate.isInitialized(chats.get(0).getChatAgencies()));
    assertEquals(2, chats.get(0).getChatAgencies().size());
  }

  @Test
  void saveShouldSaveChat() {
    givenAConsultant();
    givenAValidChat();

    var persistedChat = underTest.save(chat);

    var foundOptionalChat = underTest.findById(persistedChat.getId());
    assertTrue(foundOptionalChat.isPresent());
    var foundChat = foundOptionalChat.get();
    assertEquals(chat.isRepetitive(), foundChat.isRepetitive());
    assertEquals(chat.isActive(), foundChat.isActive());
    assertEquals(ConversationType.SELF_HELP, foundChat.getConversationType());
  }

  @Test
  void saveShouldRepairANullConversationTypeDuringRollingDeployment() {
    givenAConsultant();
    givenAValidChat();
    chat.setConversationType(null);
    var persistedChat = underTest.save(chat);
    entityManager.flush();

    persistedChat.setConversationType(ConversationType.INTERNAL_GROUP);
    underTest.save(persistedChat);
    entityManager.flush();
    entityManager.clear();

    assertEquals(
        ConversationType.INTERNAL_GROUP,
        underTest.findById(persistedChat.getId()).orElseThrow().getConversationType());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void activeChatSelectionShouldSerializeConcurrentDeactivationTransactions() throws Exception {
    var transactions = new TransactionTemplate(transactionManager);
    transactions.executeWithoutResult(
        ignored -> {
          givenAConsultant();
          givenAValidChat();
          chat.setActive(true);
          chat = underTest.save(chat);
        });

    var firstSelectionCompleted = new CountDownLatch(1);
    var releaseFirstTransaction = new CountDownLatch(1);
    var secondSelectionStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstWorker =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      ignored -> {
                        var selectedChats = underTest.findAllByActiveIsTrue();
                        assertEquals(1, selectedChats.size());
                        firstSelectionCompleted.countDown();
                        await(releaseFirstTransaction);
                        selectedChats.get(0).setActive(false);
                      }));

      assertTrue(firstSelectionCompleted.await(5, TimeUnit.SECONDS));

      var secondWorker =
          executor.submit(
              () ->
                  transactions.execute(
                      ignored -> {
                        secondSelectionStarted.countDown();
                        return underTest.findAllByActiveIsTrue().size();
                      }));

      assertTrue(secondSelectionStarted.await(5, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertThrows(
          TimeoutException.class, () -> secondWorker.get(250, TimeUnit.MILLISECONDS));

      releaseFirstTransaction.countDown();
      firstWorker.get(5, TimeUnit.SECONDS);
      assertEquals(0, secondWorker.get(5, TimeUnit.SECONDS));
    } finally {
      releaseFirstTransaction.countDown();
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interruptedException);
    }
  }

  @Test
  void findsReminderCandidatesFromLegacyRecurrenceAndMovedOccurrences() {
    givenAConsultant();
    var windowEnd = LocalDateTime.parse("2026-08-03T18:00:00");
    var windowStart = windowEnd.minusMinutes(5);
    var ordinary = reminderCandidate(windowEnd, 1, false, 0);
    var legacy = reminderCandidate(windowEnd.minusWeeks(1), 2, false, 0);
    var moved = reminderCandidate(windowEnd.plusDays(2), 1, false, 0);
    reminderCandidate(windowEnd.minusYears(1), 2, false, 0);
    reminderCandidate(windowEnd, 1, true, 0);
    reminderCandidate(windowEnd, 1, false, 1);
    entityManager.persist(
        ChatOccurrenceException.builder()
            .series(moved)
            .originalOccurrenceStartUtc(moved.getStartDate())
            .overrideStartUtc(windowStart)
            .exceptionType(ExceptionType.OVERRIDE)
            .build());
    entityManager.flush();

    var ids = Set.copyOf(underTest.findInactiveReminderCandidateIds(windowStart, windowEnd));

    assertEquals(Set.of(ordinary.getId(), legacy.getId(), moved.getId()), ids);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reminderLockWaitsForTheOccurrenceEditToCommit() throws Exception {
    var transactions = new TransactionTemplate(transactionManager);
    var start = LocalDateTime.parse("2026-08-03T18:00:00");
    transactions.executeWithoutResult(
        ignored -> {
          givenAConsultant();
          chat = reminderCandidate(start, 1, false, 0);
        });
    var seriesId = chat.getId();
    var editLocked = new CountDownLatch(1);
    var releaseEdit = new CountDownLatch(1);
    var reminderStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var editor =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      ignored -> {
                        var series =
                            underTest.findSeriesForAppointmentMailUpdate(seriesId).orElseThrow();
                        occurrenceExceptions.save(ChatOccurrenceException.skip(series, start));
                        editLocked.countDown();
                        await(releaseEdit);
                      }));
      assertTrue(editLocked.await(5, TimeUnit.SECONDS));

      var reminder =
          executor.submit(
              () ->
                  transactions.execute(
                      ignored -> {
                        reminderStarted.countDown();
                        underTest.findByIdForUpdate(seriesId).orElseThrow();
                        return occurrenceExceptions
                            .findBySeries_IdAndOriginalOccurrenceStartUtc(seriesId, start)
                            .orElseThrow()
                            .getExceptionType();
                      }));
      assertTrue(reminderStarted.await(5, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertThrows(
          TimeoutException.class, () -> reminder.get(250, TimeUnit.MILLISECONDS));
      releaseEdit.countDown();
      editor.get(5, TimeUnit.SECONDS);
      assertEquals(ExceptionType.SKIP, reminder.get(5, TimeUnit.SECONDS));
    } finally {
      releaseEdit.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      occurrenceExceptions.deleteAll(occurrenceExceptions.findBySeries_Id(seriesId));
    }
  }

  private Chat reminderCandidate(
      LocalDateTime start, int repeatCount, boolean active, int currentOccurrenceIndex) {
    givenAValidChat();
    var candidate = chat;
    chat = null;
    candidate.setInitialStartDate(start);
    candidate.setStartDate(start);
    candidate.setDuration(30);
    candidate.setRepeatCount(repeatCount);
    candidate.setRepetitive(repeatCount > 1);
    candidate.setChatInterval(repeatCount > 1 ? ChatInterval.WEEKLY : null);
    candidate.setActive(active);
    candidate.setCurrentOccurrenceIndex(currentOccurrenceIndex);
    return underTest.save(candidate);
  }

  private void givenAValidChat() {
    chat = new Chat();
    chat.setTopic(RandomStringUtils.randomAlphanumeric(1, 255));
    chat.setConsultingTypeId(1);
    chat.setInitialStartDate(LocalDateTime.now());
    chat.setStartDate(easyRandom.nextObject(LocalDateTime.class));
    chat.setDuration(easyRandom.nextInt());
    chat.setChatOwner(consultant);
    chat.setConversationType(ConversationType.SELF_HELP);
  }

  private void givenAConsultant() {
    consultant = consultantRepository.findAll().iterator().next();
  }
}
