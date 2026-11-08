package de.caritas.cob.userservice.api.service.draft;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DraftMessageService {

  private final @NonNull DraftMessageRepository draftMessageRepository;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void upsertDraft(
      String userId, String scopeKey, UpsertDraftRequest request, Long tenantId) {
    if (userId == null || userId.isBlank() || scopeKey == null || scopeKey.isBlank()) {
      return;
    }

    String text = request != null && request.getText() != null ? request.getText() : "";
    // #983: the API must not depend on every client behaving. A draft that carries no content —
    // including TipTap's empty document, which is markup rather than an empty string — deletes the
    // row instead of persisting one nothing can ever clear.
    if (!DraftContent.hasContent(text)) {
      draftMessageRepository.deleteByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(userId, scopeKey);
      return;
    }

    var now = LocalDateTime.now();
    // Serialize edits to legacy rows with retirement. After retirement, an older client
    // can still create a new legacy row rather than dropping an edit to a removed entity.
    var draft =
        draftMessageRepository
            .findForUpdateByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(userId, scopeKey)
            .map(
                existing -> {
                  existing.setText(text);
                  existing.setActionPath(request != null ? request.getActionPath() : null);
                  existing.setTitle(request != null ? request.getTitle() : null);
                  existing.setSourceSessionId(
                      request != null ? request.getSourceSessionId() : null);
                  existing.setRoomRef(request != null ? request.getRoomRef() : null);
                  existing.setThreadRootId(request != null ? request.getThreadRootId() : null);
                  existing.setUpdateDate(now);
                  return existing;
                })
            .orElse(
                DraftMessage.builder()
                    .userId(userId)
                    .scopeKey(scopeKey)
                    .text(text)
                    .actionPath(request != null ? request.getActionPath() : null)
                    .title(request != null ? request.getTitle() : null)
                    .sourceSessionId(request != null ? request.getSourceSessionId() : null)
                    .roomRef(request != null ? request.getRoomRef() : null)
                    .threadRootId(request != null ? request.getThreadRootId() : null)
                    .createDate(now)
                    .updateDate(now)
                    .tenantId(tenantId)
                    .build());

    draftMessageRepository.save(draft);
  }

  public static boolean usesProtectedNamespace(String scopeKey) {
    return scopeKey != null
        && scopeKey.regionMatches(
            true,
            0,
            DraftMessageProtection.SCOPE_NAMESPACE,
            0,
            DraftMessageProtection.SCOPE_NAMESPACE.length());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void upsertProtectedDraft(
      String userId, String scopeKey, UpsertDraftRequest request, Long tenantId) {
    requireDraftOwner(userId, tenantId);
    requireProtectedScope(scopeKey);
    if (request == null
        || hasRoutingMetadata(request)
        || !DraftMessageProtection.isEnvelope(request.getText())) {
      throw new BadRequestException("Invalid protected draft");
    }

    var draft =
        findLockedProtected(userId, scopeKey, tenantId)
            .orElseGet(() -> protectedDraft(userId, scopeKey, tenantId));
    draft.setText(request.getText());
    draft.setUpdateDate(LocalDateTime.now());
    draftMessageRepository.saveAndFlush(draft);
  }

  @Transactional(readOnly = true)
  public DraftMessageItem getProtectedDraft(String userId, String scopeKey, Long tenantId) {
    requireDraftOwner(userId, tenantId);
    requireProtectedScope(scopeKey);
    return draftMessageRepository
        .findProtected(userId, scopeKey, tenantId)
        .filter(draft -> scopeKey.equals(draft.getProtectedScopeKey()))
        .map(this::toItem)
        .orElse(null);
  }

  @Transactional
  public void deleteProtectedDraft(String userId, String scopeKey, Long tenantId) {
    requireDraftOwner(userId, tenantId);
    requireProtectedScope(scopeKey);
    findLockedProtected(userId, scopeKey, tenantId).ifPresent(draftMessageRepository::delete);
  }

  /** Create a replacement while holding the source lock; the browser verifies it separately. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void migrateDraft(String userId, Long tenantId, MigrateDraftRequest request) {
    requireDraftOwner(userId, tenantId);
    if (request == null
        || request.legacyId() == null
        || request.legacyId() <= 0
        || !DraftMessageProtection.isEnvelope(request.text())) {
      throw new BadRequestException("Invalid draft migration");
    }
    requireProtectedScope(request.targetScopeKey());
    requireRevision(request.legacyRevision());
    var legacy =
        requireUnchangedLegacy(userId, tenantId, request.legacyId(), request.legacyRevision());
    if (draftMessageRepository
        .findProtected(userId, request.targetScopeKey(), tenantId)
        .isPresent()) {
      throw new ConflictException("Protected draft already exists");
    }

    var target = protectedDraft(userId, request.targetScopeKey(), tenantId);
    target.setText(request.text());
    target.setUpdateDate(legacy.getUpdateDate());
    // An independent autosave or migration may have inserted the same target after the read.
    // The database uniqueness constraint arbitrates that race without overwriting either draft.
    draftMessageRepository.saveAndFlush(target);
  }

  @Transactional
  public void retireLegacyDraft(
      String userId,
      Long tenantId,
      long legacyId,
      String revision,
      String targetScopeKey,
      String targetRevision) {
    requireDraftOwner(userId, tenantId);
    if (legacyId <= 0) {
      throw new BadRequestException("Invalid legacy draft ID");
    }
    requireProtectedScope(targetScopeKey);
    requireRevision(revision);
    requireRevision(targetRevision);
    var legacy = requireUnchangedLegacy(userId, tenantId, legacyId, revision);
    var target =
        findLockedProtected(userId, targetScopeKey, tenantId)
            .orElseThrow(() -> new NotFoundException("Protected draft not found"));
    if (!targetRevision.equals(DraftMessageProtection.revision(target))
        || !DraftMessageProtection.isEnvelope(target.getText())) {
      throw new ConflictException("Protected draft has changed");
    }
    draftMessageRepository.delete(legacy);
  }

  private DraftMessage requireUnchangedLegacy(
      String userId, Long tenantId, long legacyId, String revision) {
    var legacy =
        draftMessageRepository
            .findOwnedLegacyForUpdate(legacyId, userId, tenantId)
            .orElseThrow(() -> new NotFoundException("Legacy draft not found"));
    if (usesProtectedNamespace(legacy.getScopeKey())
        || !revision.equals(DraftMessageProtection.revision(legacy))) {
      throw new ConflictException("Legacy draft has changed");
    }
    return legacy;
  }

  private Optional<DraftMessage> findLockedProtected(
      String userId, String scopeKey, Long tenantId) {
    return draftMessageRepository
        .findProtectedForUpdate(userId, scopeKey, tenantId)
        .filter(draft -> scopeKey.equals(draft.getProtectedScopeKey()));
  }

  private static DraftMessage protectedDraft(String userId, String scopeKey, Long tenantId) {
    var now = LocalDateTime.now();
    return DraftMessage.builder()
        .userId(userId)
        .tenantId(tenantId)
        .scopeKey(scopeKey)
        .protectedScopeKey(scopeKey)
        .createDate(now)
        .updateDate(now)
        .build();
  }

  private static void requireDraftOwner(String userId, Long tenantId) {
    if (userId == null || userId.isBlank() || (tenantId != null && tenantId <= 0)) {
      throw new ForbiddenException("Protected drafts require an authenticated owner");
    }
  }

  private static void requireProtectedScope(String scopeKey) {
    if (!DraftMessageProtection.isProtectedScope(scopeKey)) {
      throw new BadRequestException("Invalid protected draft scope");
    }
  }

  private static void requireRevision(String revision) {
    if (!DraftMessageProtection.isRevision(revision)) {
      throw new BadRequestException("Invalid draft revision");
    }
  }

  private static boolean hasRoutingMetadata(UpsertDraftRequest request) {
    return request.getActionPath() != null
        || request.getTitle() != null
        || request.getSourceSessionId() != null
        || request.getRoomRef() != null
        || request.getThreadRootId() != null;
  }

  public record MigrateDraftRequest(
      Long legacyId, String legacyRevision, String targetScopeKey, String text) {}

  @Transactional(readOnly = true)
  public DraftMessageItem getDraft(String userId, String scopeKey) {
    if (userId == null || userId.isBlank() || scopeKey == null || scopeKey.isBlank()) {
      return null;
    }
    try {
      return draftMessageRepository
          .findByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(userId, scopeKey)
          .map(this::toItem)
          .orElse(null);
    } catch (Exception e) {
      // Never let a non-critical draft lookup break the chat (e.g. duplicate rows when no
      // unique constraint exists on user_id + scope_key -> NonUniqueResultException).
      log.warn("Could not load draft for scopeKey {}: {}", scopeKey, e.getMessage());
      return null;
    }
  }

  @Transactional(readOnly = true)
  public DraftFeedResponse getDrafts(String userId, int page, int perPage) {
    int safePage = Math.max(0, page);
    int safePerPage = Math.max(1, Math.min(perPage, 200));

    List<DraftMessageItem> items =
        draftMessageRepository
            .findByUserIdOrderByUpdateDateDescIdDesc(userId, PageRequest.of(safePage, safePerPage))
            .stream()
            .map(this::toItem)
            .collect(Collectors.toList());

    return DraftFeedResponse.builder().items(items).page(safePage).perPage(safePerPage).build();
  }

  @Transactional
  public void deleteDraft(String userId, String scopeKey) {
    if (userId == null || userId.isBlank() || scopeKey == null || scopeKey.isBlank()) {
      return;
    }
    draftMessageRepository.deleteByUserIdAndScopeKeyAndProtectedScopeKeyIsNull(userId, scopeKey);
  }

  private DraftMessageItem toItem(DraftMessage draft) {
    return DraftMessageItem.builder()
        .id(draft.getId())
        .scopeKey(draft.getScopeKey())
        .revision(DraftMessageProtection.revision(draft))
        .text(draft.getText())
        .actionPath(draft.getActionPath())
        .title(draft.getTitle())
        .sourceSessionId(draft.getSourceSessionId())
        .roomRef(draft.getRoomRef())
        .threadRootId(draft.getThreadRootId())
        .updatedAt(
            draft.getUpdateDate() != null
                ? draft.getUpdateDate().atOffset(ZoneOffset.UTC).toString()
                : null)
        .build();
  }

  @Getter
  @Builder
  public static class DraftFeedResponse {
    private final List<DraftMessageItem> items;
    private final int page;
    private final int perPage;
  }

  @Getter
  @Builder
  public static class DraftMessageItem {
    private final Long id;
    private final String scopeKey;
    private final String revision;
    private final String text;
    private final String actionPath;
    private final String title;
    private final Long sourceSessionId;
    private final String roomRef;
    private final String threadRootId;
    private final String updatedAt;
  }

  @Getter
  public static class UpsertDraftRequest {
    private String text;
    private String actionPath;
    private String title;
    private Long sourceSessionId;
    private String roomRef;
    private String threadRootId;

    public void setText(String text) {
      this.text = text;
    }

    public void setActionPath(String actionPath) {
      this.actionPath = actionPath;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public void setSourceSessionId(Long sourceSessionId) {
      this.sourceSessionId = sourceSessionId;
    }

    public void setRoomRef(String roomRef) {
      this.roomRef = roomRef;
    }

    public void setThreadRootId(String threadRootId) {
      this.threadRootId = threadRootId;
    }
  }
}
