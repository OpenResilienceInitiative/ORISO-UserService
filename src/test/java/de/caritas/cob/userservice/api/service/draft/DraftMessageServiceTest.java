package de.caritas.cob.userservice.api.service.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftFeedResponse;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftMessageItem;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DraftMessageServiceTest {

  private static final String USER_ID = "user-abc";
  private static final String SCOPE_KEY = "session:123";
  private static final Long TENANT_ID = 5L;

  @Mock private DraftMessageRepository draftMessageRepository;

  @InjectMocks private DraftMessageService draftMessageService;

  @Test
  void upsertDraft_nullUserId_returnsWithoutRepositoryCall() {
    draftMessageService.upsertDraft(null, SCOPE_KEY, upsertRequest("text"), TENANT_ID);

    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void upsertDraft_blankUserId_returnsWithoutRepositoryCall() {
    draftMessageService.upsertDraft("   ", SCOPE_KEY, upsertRequest("text"), TENANT_ID);

    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void upsertDraft_nullScopeKey_returnsWithoutRepositoryCall() {
    draftMessageService.upsertDraft(USER_ID, null, upsertRequest("text"), TENANT_ID);

    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void upsertDraft_blankScopeKey_returnsWithoutRepositoryCall() {
    draftMessageService.upsertDraft(USER_ID, "", upsertRequest("text"), TENANT_ID);

    verifyNoInteractions(draftMessageRepository);
  }

  // Clearing the editor should remove persisted drafts instead of storing empty rows.
  @Test
  void upsertDraft_nullRequest_deletesExistingDraft() {
    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, null, TENANT_ID);

    verify(draftMessageRepository).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
    verify(draftMessageRepository, never()).save(any());
  }

  @Test
  void upsertDraft_nullText_deletesExistingDraft() {
    UpsertDraftRequest request = new UpsertDraftRequest();
    request.setText(null);

    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, request, TENANT_ID);

    verify(draftMessageRepository).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
    verify(draftMessageRepository, never()).save(any());
  }

  @Test
  void upsertDraft_blankText_deletesExistingDraft() {
    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, upsertRequest("  "), TENANT_ID);

    verify(draftMessageRepository).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
    verify(draftMessageRepository, never()).save(any());
  }

  // #983: TipTap serialises an empty document as markup, so the emptiness check must look past
  // the tags — otherwise merely visiting a conversation persists a zero-content draft row.
  @Test
  void upsertDraft_emptyTipTapDocument_deletesExistingDraft() {
    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, upsertRequest("<p></p>"), TENANT_ID);

    verify(draftMessageRepository).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
    verify(draftMessageRepository, never()).save(any());
  }

  @Test
  void upsertDraft_markupOnlyDraft_deletesExistingDraft() {
    draftMessageService.upsertDraft(
        USER_ID, SCOPE_KEY, upsertRequest("<p><br></p><p>&nbsp;</p>"), TENANT_ID);

    verify(draftMessageRepository).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
    verify(draftMessageRepository, never()).save(any());
  }

  // #983: an E2EE draft is opaque ciphertext without markup and must never be treated as empty.
  @Test
  void upsertDraft_encryptedDraft_isPersisted() {
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.empty());

    draftMessageService.upsertDraft(
        USER_ID, SCOPE_KEY, upsertRequest("AwgBmE3yLpFhZ0uK+ciphertext=="), TENANT_ID);

    ArgumentCaptor<DraftMessage> captor = ArgumentCaptor.forClass(DraftMessage.class);
    verify(draftMessageRepository).save(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("AwgBmE3yLpFhZ0uK+ciphertext==");
    verify(draftMessageRepository, never()).deleteByUserIdAndScopeKey(any(), any());
  }

  // First keystroke for a scope creates a new draft row with tenant attribution.
  @Test
  void upsertDraft_noExistingDraft_savesNewDraftWithAllFields() {
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.empty());

    UpsertDraftRequest request = fullUpsertRequest("Hello draft");
    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, request, TENANT_ID);

    ArgumentCaptor<DraftMessage> captor = ArgumentCaptor.forClass(DraftMessage.class);
    verify(draftMessageRepository).save(captor.capture());
    DraftMessage saved = captor.getValue();

    assertThat(saved.getUserId()).isEqualTo(USER_ID);
    assertThat(saved.getScopeKey()).isEqualTo(SCOPE_KEY);
    assertThat(saved.getText()).isEqualTo("Hello draft");
    assertThat(saved.getActionPath()).isEqualTo("/action");
    assertThat(saved.getTitle()).isEqualTo("Title");
    assertThat(saved.getSourceSessionId()).isEqualTo(99L);
    assertThat(saved.getRoomRef()).isEqualTo("room-ref");
    assertThat(saved.getThreadRootId()).isEqualTo("thread-root");
    assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(saved.getCreateDate()).isNotNull();
    assertThat(saved.getUpdateDate()).isNotNull();
    assertThat(saved.getCreateDate()).isEqualTo(saved.getUpdateDate());
  }

  // Autosave must update the same row so chat drafts keep their identity and created timestamp.
  @Test
  void upsertDraft_existingDraft_updatesEntityInPlace() {
    LocalDateTime originalCreateDate = LocalDateTime.of(2024, 1, 1, 10, 0);
    DraftMessage existing =
        DraftMessage.builder()
            .id(1L)
            .userId(USER_ID)
            .scopeKey(SCOPE_KEY)
            .text("old")
            .actionPath("/old")
            .title("old title")
            .sourceSessionId(1L)
            .roomRef("old-room")
            .threadRootId("old-thread")
            .createDate(originalCreateDate)
            .updateDate(originalCreateDate)
            .tenantId(TENANT_ID)
            .build();
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.of(existing));

    UpsertDraftRequest request = fullUpsertRequest("updated text");
    draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, request, TENANT_ID);

    ArgumentCaptor<DraftMessage> captor = ArgumentCaptor.forClass(DraftMessage.class);
    verify(draftMessageRepository).save(captor.capture());
    DraftMessage saved = captor.getValue();

    assertThat(saved).isSameAs(existing);
    assertThat(saved.getText()).isEqualTo("updated text");
    assertThat(saved.getActionPath()).isEqualTo("/action");
    assertThat(saved.getTitle()).isEqualTo("Title");
    assertThat(saved.getSourceSessionId()).isEqualTo(99L);
    assertThat(saved.getRoomRef()).isEqualTo("room-ref");
    assertThat(saved.getThreadRootId()).isEqualTo("thread-root");
    assertThat(saved.getCreateDate()).isEqualTo(originalCreateDate);
    assertThat(saved.getUpdateDate()).isAfter(originalCreateDate);
  }

  @Test
  void getDraft_existingDraft_returnsMappedItem() {
    LocalDateTime updateDate = LocalDateTime.of(2024, 6, 15, 12, 30);
    DraftMessage draft =
        DraftMessage.builder()
            .id(10L)
            .scopeKey(SCOPE_KEY)
            .text("draft text")
            .actionPath("/path")
            .title("Draft title")
            .sourceSessionId(55L)
            .roomRef("room")
            .threadRootId("thread")
            .updateDate(updateDate)
            .build();
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.of(draft));

    DraftMessageItem item = draftMessageService.getDraft(USER_ID, SCOPE_KEY);

    assertThat(item.getId()).isEqualTo(10L);
    assertThat(item.getScopeKey()).isEqualTo(SCOPE_KEY);
    assertThat(item.getText()).isEqualTo("draft text");
    assertThat(item.getActionPath()).isEqualTo("/path");
    assertThat(item.getTitle()).isEqualTo("Draft title");
    assertThat(item.getSourceSessionId()).isEqualTo(55L);
    assertThat(item.getRoomRef()).isEqualTo("room");
    assertThat(item.getThreadRootId()).isEqualTo("thread");
    assertThat(item.getUpdatedAt()).isEqualTo("2024-06-15T12:30Z");
  }

  @Test
  void getDraft_noDraft_returnsNull() {
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.empty());

    assertThat(draftMessageService.getDraft(USER_ID, SCOPE_KEY)).isNull();
  }

  @Test
  void getDraft_nullUserId_returnsNullWithoutRepositoryCall() {
    assertThat(draftMessageService.getDraft(null, SCOPE_KEY)).isNull();
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void getDraft_blankUserId_returnsNullWithoutRepositoryCall() {
    assertThat(draftMessageService.getDraft("  ", SCOPE_KEY)).isNull();
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void getDraft_nullScopeKey_returnsNullWithoutRepositoryCall() {
    assertThat(draftMessageService.getDraft(USER_ID, null)).isNull();
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void getDraft_blankScopeKey_returnsNullWithoutRepositoryCall() {
    assertThat(draftMessageService.getDraft(USER_ID, "")).isNull();
    verifyNoInteractions(draftMessageRepository);
  }

  // Duplicate rows must not break chat loading when the unique index is missing.
  @Test
  void getDraft_repositoryThrowsException_returnsNull() {
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(2));

    assertThat(draftMessageService.getDraft(USER_ID, SCOPE_KEY)).isNull();
  }

  @Test
  void getDraft_nullUpdateDate_mapsUpdatedAtAsNull() {
    DraftMessage draft =
        DraftMessage.builder().id(1L).scopeKey(SCOPE_KEY).text("x").updateDate(null).build();
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.of(draft));

    DraftMessageItem item = draftMessageService.getDraft(USER_ID, SCOPE_KEY);

    assertThat(item.getUpdatedAt()).isNull();
  }

  @Test
  void getDrafts_happyPath_returnsPaginatedFeedResponse() {
    DraftMessage draft =
        DraftMessage.builder()
            .id(1L)
            .scopeKey(SCOPE_KEY)
            .text("feed item")
            .updateDate(LocalDateTime.of(2024, 3, 1, 8, 0))
            .build();
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of(draft));

    DraftFeedResponse response = draftMessageService.getDrafts(USER_ID, 1, 50);

    assertThat(response.getPage()).isEqualTo(1);
    assertThat(response.getPerPage()).isEqualTo(50);
    assertThat(response.getItems()).hasSize(1);
    assertThat(response.getItems().get(0).getText()).isEqualTo("feed item");
  }

  @Test
  void getDrafts_negativePage_clampsPageToZero() {
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of());

    DraftFeedResponse response = draftMessageService.getDrafts(USER_ID, -5, 10);

    assertThat(response.getPage()).isZero();
    assertThat(response.getPerPage()).isEqualTo(10);
  }

  @Test
  void getDrafts_zeroPerPage_clampsPerPageToOne() {
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of());

    DraftFeedResponse response = draftMessageService.getDrafts(USER_ID, 0, 0);

    assertThat(response.getPage()).isZero();
    assertThat(response.getPerPage()).isEqualTo(1);
  }

  @Test
  void getDrafts_perPageAboveMax_clampsPerPageToTwoHundred() {
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of());

    DraftFeedResponse response = draftMessageService.getDrafts(USER_ID, 0, 500);

    assertThat(response.getPerPage()).isEqualTo(200);
  }

  @Test
  void getDrafts_perPageWithinRange_keepsPerPageUnchanged() {
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of());

    DraftFeedResponse response = draftMessageService.getDrafts(USER_ID, 0, 100);

    assertThat(response.getPerPage()).isEqualTo(100);
  }

  @Test
  void getDrafts_validPagination_forwardsClampedPageRequestToRepository() {
    when(draftMessageRepository.findByUserIdOrderByUpdateDateDesc(eq(USER_ID), any(Pageable.class)))
        .thenReturn(List.of());

    draftMessageService.getDrafts(USER_ID, -5, 500);

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(draftMessageRepository)
        .findByUserIdOrderByUpdateDateDesc(eq(USER_ID), pageableCaptor.capture());
    Pageable pageable = pageableCaptor.getValue();
    assertThat(pageable).isEqualTo(PageRequest.of(0, 200));
  }

  @Test
  void deleteDraft_validInput_deletesOnce() {
    draftMessageService.deleteDraft(USER_ID, SCOPE_KEY);

    verify(draftMessageRepository, times(1)).deleteByUserIdAndScopeKey(USER_ID, SCOPE_KEY);
  }

  @Test
  void deleteDraft_nullUserId_skipsRepositoryCall() {
    draftMessageService.deleteDraft(null, SCOPE_KEY);
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void deleteDraft_blankUserId_skipsRepositoryCall() {
    draftMessageService.deleteDraft("  ", SCOPE_KEY);
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void deleteDraft_nullScopeKey_skipsRepositoryCall() {
    draftMessageService.deleteDraft(USER_ID, null);
    verifyNoInteractions(draftMessageRepository);
  }

  @Test
  void deleteDraft_blankScopeKey_skipsRepositoryCall() {
    draftMessageService.deleteDraft(USER_ID, "");
    verifyNoInteractions(draftMessageRepository);
  }

  // Race on first insert is handled in the controller; the service itself still propagates DB
  // errors.
  @Test
  void upsertDraft_concurrentInsertRace_dataIntegrityViolationBubblesOut() throws Exception {
    UpsertDraftRequest request = upsertRequest("concurrent text");
    when(draftMessageRepository.findByUserIdAndScopeKey(USER_ID, SCOPE_KEY))
        .thenReturn(Optional.empty());

    AtomicInteger saveAttempts = new AtomicInteger();
    when(draftMessageRepository.save(any(DraftMessage.class)))
        .thenAnswer(
            invocation -> {
              if (saveAttempts.incrementAndGet() > 1) {
                throw new DataIntegrityViolationException("duplicate user_id + scope_key");
              }
              return invocation.getArgument(0);
            });

    CountDownLatch threadsReady = new CountDownLatch(2);
    CountDownLatch releaseThreads = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      var first =
          executor.submit(
              () -> {
                threadsReady.countDown();
                try {
                  releaseThreads.await(5, TimeUnit.SECONDS);
                  draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, request, TENANT_ID);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
              });
      var second =
          executor.submit(
              () -> {
                threadsReady.countDown();
                try {
                  releaseThreads.await(5, TimeUnit.SECONDS);
                  draftMessageService.upsertDraft(USER_ID, SCOPE_KEY, request, TENANT_ID);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
              });

      threadsReady.await(5, TimeUnit.SECONDS);
      releaseThreads.countDown();

      int failures = 0;
      for (var future : List.of(first, second)) {
        try {
          future.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(DataIntegrityViolationException.class);
          failures++;
        }
      }
      assertThat(failures).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }

    // Known gap: DraftMessageController catches DataAccessException; service has no local handler.
    verify(draftMessageRepository, times(2)).save(any(DraftMessage.class));
  }

  private static UpsertDraftRequest upsertRequest(String text) {
    UpsertDraftRequest request = new UpsertDraftRequest();
    request.setText(text);
    return request;
  }

  private static UpsertDraftRequest fullUpsertRequest(String text) {
    UpsertDraftRequest request = upsertRequest(text);
    request.setActionPath("/action");
    request.setTitle("Title");
    request.setSourceSessionId(99L);
    request.setRoomRef("room-ref");
    request.setThreadRootId("thread-root");
    return request;
  }
}
