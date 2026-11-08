package de.caritas.cob.userservice.api.service.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.DraftMessageItem;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.MigrateDraftRequest;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DraftMessageService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DraftMessageMigrationIT {

  private static final long TENANT = 9L;
  private static final String TARGET = "draft.v1." + "a".repeat(43);
  private static final String ENVELOPE = "draft.v1." + "b".repeat(16) + "." + "c".repeat(32);
  private static final String LARGE_ENVELOPE =
      "draft.v1." + "b".repeat(16) + "." + "c".repeat(90_000);

  @Autowired private DraftMessageService service;
  @Autowired private DraftMessageRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void verifiesReplacementAndRetiresOnlyTheExactLegacySnapshot() {
    String owner = UUID.randomUUID().toString();
    var legacy = legacy(owner, "scope:first|thread:main", TENANT);

    service.migrateDraft(owner, TENANT, migration(legacy, TARGET, LARGE_ENVELOPE));
    assertThat(repository.findById(legacy.getId())).isPresent();
    var target = service.getProtectedDraft(owner, TARGET, TENANT);
    assertThat(target.getText()).isEqualTo(LARGE_ENVELOPE);
    assertThat(target.getScopeKey()).isEqualTo(TARGET);
    assertThat(target.getUpdatedAt()).isEqualTo(legacy.getUpdatedAt());
    assertThat(target.getActionPath()).isNull();
    assertThat(target.getTitle()).isNull();
    assertThat(target.getSourceSessionId()).isNull();
    assertThat(target.getRoomRef()).isNull();
    assertThat(target.getThreadRootId()).isNull();
    assertThat(service.getProtectedDrafts(owner, TENANT, 0, 200, false).getItems())
        .extracting(DraftMessageItem::getRevision)
        .containsExactlyInAnyOrder(legacy.getRevision(), target.getRevision());

    service.retireLegacyDraft(
        owner, TENANT, legacy.getId(), legacy.getRevision(), TARGET, target.getRevision());

    assertThat(repository.findById(legacy.getId())).isEmpty();
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT).getRevision())
        .isEqualTo(target.getRevision());
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    legacy.getId(),
                    legacy.getRevision(),
                    TARGET,
                    target.getRevision()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void refusesStaleSnapshotsExistingTargetsAndCrossTenantOrUserAccess() {
    String owner = UUID.randomUUID().toString();
    var legacy = legacy(owner, "scope:private|thread:main", TENANT);
    assertThatThrownBy(
            () -> service.migrateDraft(owner, TENANT + 1, migration(legacy, TARGET, ENVELOPE)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.migrateDraft("another-owner", TENANT, migration(legacy, TARGET, ENVELOPE)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> service.migrateDraft(owner, TENANT, migration(legacy, TARGET, "plaintext")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.migrateDraft(
                    owner, TENANT, migration(legacy, "scope:private|thread:main", ENVELOPE)))
        .isInstanceOf(BadRequestException.class);

    var source = repository.findById(legacy.getId()).orElseThrow();
    source.setTitle("Changed in the same second");
    repository.saveAndFlush(source);
    assertThatThrownBy(
            () -> service.migrateDraft(owner, TENANT, migration(legacy, TARGET, ENVELOPE)))
        .isInstanceOf(ConflictException.class);
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT)).isNull();

    var changedLegacy = service.getDraft(owner, legacy.getScopeKey());
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    changedLegacy.getId(),
                    changedLegacy.getRevision(),
                    TARGET,
                    changedLegacy.getRevision()))
        .isInstanceOf(NotFoundException.class);
    assertThat(repository.findById(changedLegacy.getId())).isPresent();
    service.migrateDraft(owner, TENANT, migration(changedLegacy, TARGET, ENVELOPE));
    var target = service.getProtectedDraft(owner, TARGET, TENANT);
    assertThatThrownBy(
            () ->
                service.migrateDraft(
                    owner, TENANT, migration(changedLegacy, TARGET, LARGE_ENVELOPE)))
        .isInstanceOf(ConflictException.class);
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT + 1)).isNull();
    assertThat(service.getProtectedDraft(owner, TARGET, null)).isNull();
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT).getText()).isEqualTo(ENVELOPE);

    service.upsertProtectedDraft(owner, TARGET, payload(LARGE_ENVELOPE), TENANT);
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    changedLegacy.getId(),
                    changedLegacy.getRevision(),
                    TARGET,
                    target.getRevision()))
        .isInstanceOf(ConflictException.class);

    var changedTarget = service.getProtectedDraft(owner, TARGET, TENANT);
    source = repository.findById(changedLegacy.getId()).orElseThrow();
    source.setRoomRef("changed-after-protected-write");
    repository.saveAndFlush(source);
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    changedLegacy.getId(),
                    changedLegacy.getRevision(),
                    TARGET,
                    changedTarget.getRevision()))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    changedTarget.getId(),
                    changedTarget.getRevision(),
                    TARGET,
                    changedTarget.getRevision()))
        .isInstanceOf(NotFoundException.class);
    assertThat(repository.findById(changedLegacy.getId())).isPresent();
    assertThat(repository.findById(changedTarget.getId())).isPresent();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(longs = 1L)
  void standaloneAndTenantOneMayMigrateTheirPreTenancyDraft(Long requestTenant) {
    String owner = UUID.randomUUID().toString();
    var legacy = legacy(owner, "scope:historic|thread:main", null);
    assertThatThrownBy(
            () -> service.migrateDraft(owner, TENANT, migration(legacy, TARGET, ENVELOPE)))
        .isInstanceOf(NotFoundException.class);

    service.migrateDraft(owner, requestTenant, migration(legacy, TARGET, ENVELOPE));
    var target = service.getProtectedDraft(owner, TARGET, requestTenant);
    assertThat(target.getText()).isEqualTo(ENVELOPE);
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT)).isNull();
    assertThatThrownBy(
            () ->
                service.retireLegacyDraft(
                    owner,
                    TENANT,
                    legacy.getId(),
                    legacy.getRevision(),
                    TARGET,
                    target.getRevision()))
        .isInstanceOf(NotFoundException.class);
    assertThat(repository.findById(legacy.getId())).isPresent();

    service.retireLegacyDraft(
        owner, requestTenant, legacy.getId(), legacy.getRevision(), TARGET, target.getRevision());
    assertThat(repository.findById(legacy.getId())).isEmpty();
  }

  @Test
  void protectedFeedIsBoundedAndStableAcrossPagesWhileLegacyFeedExcludesCiphertext() {
    String owner = UUID.randomUUID().toString();
    var source = legacy(owner, "scope:source|thread:main", TENANT);
    var expected = new ArrayList<Long>();
    expected.add(source.getId());
    for (int i = 0; i < 12; i++) {
      expected.add(legacy(owner, "scope:case-" + i + "|thread:main", TENANT).getId());
    }
    legacy(owner, "scope:other-tenant|thread:main", TENANT + 1);
    legacy("different-owner", "scope:other-owner|thread:main", TENANT);
    service.migrateDraft(owner, TENANT, migration(source, TARGET, ENVELOPE));
    var target = service.getProtectedDraft(owner, TARGET, TENANT);
    expected.add(target.getId());
    Collections.reverse(expected);

    var first = service.getProtectedDrafts(owner, TENANT, 0, 200, false);
    var second = service.getProtectedDrafts(owner, TENANT, 1, 200, false);
    assertThat(first.getPerPage()).isEqualTo(12);
    assertThat(first.getItems()).hasSize(12);
    assertThat(second.getPerPage()).isEqualTo(12);
    assertThat(second.getPage()).isEqualTo(1);
    List<DraftMessageItem> paged = new ArrayList<>(first.getItems());
    paged.addAll(second.getItems());
    assertThat(paged).extracting(DraftMessageItem::getId).containsExactlyElementsOf(expected);
    assertThat(service.getDrafts(owner, 0, 200).getItems())
        .extracting(DraftMessageItem::getScopeKey)
        .doesNotContain(TARGET);
  }

  @Test
  void legacyOnlyScanPaginatesFiftyRowsWithinTheSameOwnerAndTenant() {
    String owner = UUID.randomUUID().toString();
    var expected = new ArrayList<Long>();
    for (int i = 0; i < 51; i++) {
      expected.add(legacy(owner, "legacy-scan:" + i, TENANT).getId());
    }
    legacy(owner, "legacy-scan:other-tenant", TENANT + 1);
    legacy("different-owner", "legacy-scan:other-owner", TENANT);
    service.upsertProtectedDraft(owner, TARGET, payload(ENVELOPE), TENANT);
    Collections.reverse(expected);

    var first = service.getProtectedDrafts(owner, TENANT, 0, 200, true);
    var second = service.getProtectedDrafts(owner, TENANT, 1, 50, true);

    assertThat(first.getPerPage()).isEqualTo(50);
    assertThat(first.getItems())
        .extracting(DraftMessageItem::getId)
        .containsExactlyElementsOf(expected.subList(0, 50));
    assertThat(second.getPage()).isEqualTo(1);
    assertThat(second.getItems())
        .extracting(DraftMessageItem::getId)
        .containsExactly(expected.get(50));
    assertThat(service.getProtectedDrafts(owner, TENANT, 0, 200, false).getItems())
        .extracting(DraftMessageItem::getScopeKey)
        .contains(TARGET);
  }

  @Test
  void twoConcurrentMigrationsCannotCreateOrOverwriteTheSameProtectedScope() throws Exception {
    String owner = UUID.randomUUID().toString();
    var first = legacy(owner, "scope:first|thread:main", TENANT);
    var second = legacy(owner, "scope:second|thread:main", TENANT);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var firstResult = threads.submit(() -> compete(owner, first, ENVELOPE, ready, start));
      var secondResult = threads.submit(() -> compete(owner, second, LARGE_ENVELOPE, ready, start));
      try {
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      boolean firstWon = firstResult.get(15, TimeUnit.SECONDS);
      boolean secondWon = secondResult.get(15, TimeUnit.SECONDS);
      assertThat(firstWon).isNotEqualTo(secondWon);
      assertThat(service.getProtectedDraft(owner, TARGET, TENANT).getText())
          .isEqualTo(firstWon ? ENVELOPE : LARGE_ENVELOPE);
    }
    assertThat(service.getProtectedDrafts(owner, TENANT, 0, 200, false).getItems()).hasSize(3);
    assertThat(repository.findById(first.getId())).isPresent();
    assertThat(repository.findById(second.getId())).isPresent();
  }

  @Test
  void legacyEditWaitingForRetirementPersistsAsANewDraft() throws Exception {
    String owner = UUID.randomUUID().toString();
    var legacy = legacy(owner, "scope:editing|thread:main", TENANT);
    service.migrateDraft(owner, TENANT, migration(legacy, TARGET, ENVELOPE));
    var target = service.getProtectedDraft(owner, TARGET, TENANT);
    var editStarted = new CountDownLatch(1);
    String edit = "A new edit from an older client";

    try (var editor = Executors.newSingleThreadExecutor()) {
      var editResult =
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    service.retireLegacyDraft(
                        owner,
                        TENANT,
                        legacy.getId(),
                        legacy.getRevision(),
                        TARGET,
                        target.getRevision());
                    var pending =
                        editor.submit(
                            () -> {
                              editStarted.countDown();
                              service.upsertDraft(
                                  owner, legacy.getScopeKey(), payload(edit), TENANT);
                            });
                    try {
                      assertThat(editStarted.await(5, TimeUnit.SECONDS)).isTrue();
                      assertThatThrownBy(() -> pending.get(250, TimeUnit.MILLISECONDS))
                          .isInstanceOf(TimeoutException.class);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(exception);
                    }
                    return pending;
                  });
      editResult.get(10, TimeUnit.SECONDS);
    }

    var newLegacy = service.getDraft(owner, legacy.getScopeKey());
    assertThat(newLegacy.getId()).isNotEqualTo(legacy.getId());
    assertThat(newLegacy.getText()).isEqualTo(edit);
    assertThat(service.getProtectedDraft(owner, TARGET, TENANT).getRevision())
        .isEqualTo(target.getRevision());
  }

  @Test
  void protectedPatchRejectsPlaintextMetadataAndEmptyPayloadWithoutChangingTheRow() {
    String owner = UUID.randomUUID().toString();
    service.upsertProtectedDraft(owner, TARGET, payload(ENVELOPE), TENANT);
    var invalidMetadata = payload(LARGE_ENVELOPE);
    invalidMetadata.setRoomRef("plaintext-room-reference");

    assertThatThrownBy(() -> service.upsertProtectedDraft(owner, TARGET, invalidMetadata, TENANT))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> service.upsertProtectedDraft(owner, TARGET, payload("plaintext"), TENANT))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.upsertProtectedDraft(owner, TARGET, payload(""), TENANT))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.upsertProtectedDraft(
                    owner, TARGET, payload(ENVELOPE + "c".repeat(1_048_576)), TENANT))
        .isInstanceOf(BadRequestException.class);
    service.deleteDraft(owner, TARGET);

    assertThat(service.getProtectedDraft(owner, TARGET, TENANT).getText()).isEqualTo(ENVELOPE);
  }

  private DraftMessageItem legacy(String owner, String scope, Long tenant) {
    var row =
        repository.saveAndFlush(
            DraftMessage.builder()
                .userId(owner)
                .tenantId(tenant)
                .scopeKey(scope)
                .text("A retained synthetic draft")
                .title("Original private title")
                .actionPath("/sessions/example")
                .roomRef("original-room")
                .createDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updateDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
    return service.getDraft(owner, row.getScopeKey());
  }

  private boolean compete(
      String owner,
      DraftMessageItem source,
      String ciphertext,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent migration did not start");
    }
    try {
      service.migrateDraft(owner, TENANT, migration(source, TARGET, ciphertext));
      return true;
    } catch (ConflictException | DataIntegrityViolationException conflict) {
      return false;
    }
  }

  private static MigrateDraftRequest migration(
      DraftMessageItem source, String scope, String ciphertext) {
    return new MigrateDraftRequest(source.getId(), source.getRevision(), scope, ciphertext);
  }

  private static UpsertDraftRequest payload(String text) {
    var payload = new UpsertDraftRequest();
    payload.setText(text);
    return payload;
  }
}
