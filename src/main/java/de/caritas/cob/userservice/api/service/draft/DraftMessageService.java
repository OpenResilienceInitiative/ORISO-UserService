package de.caritas.cob.userservice.api.service.draft;

import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DraftMessageService {

  private final @NonNull DraftMessageRepository draftMessageRepository;

  @Transactional
  public void upsertDraft(String userId, String scopeKey, UpsertDraftRequest request, Long tenantId) {
    if (userId == null || userId.isBlank() || scopeKey == null || scopeKey.isBlank()) {
      return;
    }

    String text = request != null && request.getText() != null ? request.getText() : "";
    if (text.isBlank()) {
      draftMessageRepository.deleteByUserIdAndScopeKey(userId, scopeKey);
      return;
    }

    var now = LocalDateTime.now();
    var draft =
        draftMessageRepository
            .findByUserIdAndScopeKey(userId, scopeKey)
            .map(
                existing -> {
                  existing.setText(text);
                  existing.setActionPath(request != null ? request.getActionPath() : null);
                  existing.setTitle(request != null ? request.getTitle() : null);
                  existing.setSourceSessionId(request != null ? request.getSourceSessionId() : null);
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

  @Transactional(readOnly = true)
  public DraftMessageItem getDraft(String userId, String scopeKey) {
    return draftMessageRepository
        .findByUserIdAndScopeKey(userId, scopeKey)
        .map(this::toItem)
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public DraftFeedResponse getDrafts(String userId, int page, int perPage) {
    int safePage = Math.max(0, page);
    int safePerPage = Math.max(1, Math.min(perPage, 200));

    List<DraftMessageItem> items =
        draftMessageRepository
            .findByUserIdOrderByUpdateDateDesc(userId, PageRequest.of(safePage, safePerPage))
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
    draftMessageRepository.deleteByUserIdAndScopeKey(userId, scopeKey);
  }

  private DraftMessageItem toItem(DraftMessage draft) {
    return DraftMessageItem.builder()
        .id(draft.getId())
        .scopeKey(draft.getScopeKey())
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

