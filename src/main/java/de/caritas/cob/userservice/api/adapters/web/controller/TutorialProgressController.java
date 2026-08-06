package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.TutorialProgressItem;
import de.caritas.cob.userservice.api.service.tutorial.TutorialProgressService.UpsertTutorialProgressRequest;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned tutorial progress of the authenticated user (epic TOUR-03). Reads and writes are always
 * scoped to the caller — there is no way to address another user's progress.
 */
@RestController
@RequestMapping("/users/tutorials/progress")
@RequiredArgsConstructor
public class TutorialProgressController {

  private final @NonNull TutorialProgressService tutorialProgressService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping
  public ResponseEntity<List<TutorialProgressItem>> getOwnProgress(
      @RequestParam(defaultValue = "frontend") String surface) {
    return ResponseEntity.ok(
        tutorialProgressService.getOwnProgress(authenticatedUser.getUserId(), surface));
  }

  @PutMapping
  public ResponseEntity<TutorialProgressItem> upsertOwnProgress(
      @RequestBody UpsertTutorialProgressRequest request) {
    return ResponseEntity.ok(
        tutorialProgressService.upsertOwnProgress(
            authenticatedUser.getUserId(), TenantContext.getCurrentTenant(), request));
  }
}
