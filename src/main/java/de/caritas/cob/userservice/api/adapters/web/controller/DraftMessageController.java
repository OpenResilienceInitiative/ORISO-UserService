package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.MigrateDraftRequest;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/drafts")
@RequiredArgsConstructor
@Slf4j
public class DraftMessageController {

  private final @NonNull DraftMessageService draftMessageService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping
  public ResponseEntity<DraftMessageService.DraftFeedResponse> getDrafts(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "200") @Min(1) int perPage) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(draftMessageService.getDrafts(authenticatedUser.getUserId(), page, perPage));
  }

  @GetMapping("/protected")
  public ResponseEntity<DraftMessageService.DraftFeedResponse> getProtectedDrafts(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "12") @Min(1) int perPage,
      @RequestParam(defaultValue = "false") boolean legacyOnly) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            draftMessageService.getProtectedDrafts(
                authenticatedUser.getUserId(),
                TenantContext.getCurrentTenant(),
                page,
                perPage,
                legacyOnly));
  }

  @GetMapping("/single")
  public ResponseEntity<DraftMessageService.DraftMessageItem> getDraft(
      @RequestParam @NotBlank String scopeKey) {
    var userId = authenticatedUser.getUserId();
    var item =
        DraftMessageService.usesProtectedNamespace(scopeKey)
            ? draftMessageService.getProtectedDraft(
                userId, scopeKey, TenantContext.getCurrentTenant())
            : draftMessageService.getDraft(userId, scopeKey);
    if (item == null) {
      return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(item);
  }

  @PatchMapping
  public ResponseEntity<Void> upsertDraft(
      @RequestParam @NotBlank String scopeKey, @RequestBody UpsertDraftRequest request) {
    if (DraftMessageService.usesProtectedNamespace(scopeKey)) {
      return upsertProtectedDraft(scopeKey, request);
    }
    try {
      draftMessageService.upsertDraft(
          authenticatedUser.getUserId(), scopeKey, request, TenantContext.getCurrentTenant());
    } catch (org.springframework.dao.DataAccessException e) {
      // Legacy clients treat autosave as best-effort and re-save on the next keystroke.
      // The protected write endpoint reports conflicts instead.
      log.warn("Draft upsert conflict for scopeKey {}: {}", scopeKey, e.getMessage());
    }
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PatchMapping("/protected")
  public ResponseEntity<Void> upsertProtectedDraft(
      @RequestParam @NotBlank String scopeKey, @RequestBody UpsertDraftRequest request) {
    try {
      draftMessageService.upsertProtectedDraft(
          authenticatedUser.getUserId(), scopeKey, request, TenantContext.getCurrentTenant());
    } catch (DataIntegrityViolationException | PessimisticLockingFailureException exception) {
      throw new ConflictException("Protected draft changed concurrently");
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> deleteDraft(@RequestParam @NotBlank String scopeKey) {
    if (DraftMessageService.usesProtectedNamespace(scopeKey)) {
      return deleteProtectedDraft(scopeKey);
    }
    draftMessageService.deleteDraft(authenticatedUser.getUserId(), scopeKey);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/protected")
  public ResponseEntity<Void> deleteProtectedDraft(@RequestParam @NotBlank String scopeKey) {
    try {
      draftMessageService.deleteProtectedDraft(
          authenticatedUser.getUserId(), scopeKey, TenantContext.getCurrentTenant());
    } catch (PessimisticLockingFailureException exception) {
      throw new ConflictException("Protected draft changed concurrently");
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/migrate")
  public ResponseEntity<Void> migrateDraft(@RequestBody MigrateDraftRequest request) {
    try {
      draftMessageService.migrateDraft(
          authenticatedUser.getUserId(), TenantContext.getCurrentTenant(), request);
    } catch (DataIntegrityViolationException | PessimisticLockingFailureException exception) {
      throw new ConflictException("Draft changed concurrently");
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/legacy/{id}")
  public ResponseEntity<Void> retireLegacyDraft(
      @PathVariable long id,
      @RequestParam String revision,
      @RequestParam String targetScopeKey,
      @RequestParam String targetRevision) {
    try {
      draftMessageService.retireLegacyDraft(
          authenticatedUser.getUserId(),
          TenantContext.getCurrentTenant(),
          id,
          revision,
          targetScopeKey,
          targetRevision);
    } catch (PessimisticLockingFailureException exception) {
      throw new ConflictException("Draft changed concurrently");
    }
    return ResponseEntity.noContent().build();
  }
}
