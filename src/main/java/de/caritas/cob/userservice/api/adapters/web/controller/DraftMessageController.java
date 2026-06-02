package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/drafts")
@RequiredArgsConstructor
public class DraftMessageController {

  private final @NonNull DraftMessageService draftMessageService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping
  public ResponseEntity<DraftMessageService.DraftFeedResponse> getDrafts(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "200") @Min(1) int perPage) {
    return ResponseEntity.ok(draftMessageService.getDrafts(authenticatedUser.getUserId(), page, perPage));
  }

  @GetMapping("/single")
  public ResponseEntity<DraftMessageService.DraftMessageItem> getDraft(
      @RequestParam @NotBlank String scopeKey) {
    var item = draftMessageService.getDraft(authenticatedUser.getUserId(), scopeKey);
    if (item == null) {
      return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
    return ResponseEntity.ok(item);
  }

  @PatchMapping
  public ResponseEntity<Void> upsertDraft(
      @RequestParam @NotBlank String scopeKey, @RequestBody UpsertDraftRequest request) {
    draftMessageService.upsertDraft(
        authenticatedUser.getUserId(), scopeKey, request, TenantContext.getCurrentTenant());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @DeleteMapping
  public ResponseEntity<Void> deleteDraft(@RequestParam @NotBlank String scopeKey) {
    draftMessageService.deleteDraft(authenticatedUser.getUserId(), scopeKey);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }
}


