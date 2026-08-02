package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.support.SupportAccessAuditService;
import de.caritas.cob.userservice.api.service.support.SupportAccessAuditService.SupportAccessAuditItem;
import de.caritas.cob.userservice.api.service.support.SupportTargetService;
import de.caritas.cob.userservice.api.service.support.SupportTargetService.SupportTargetItem;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-side reads for support access (ADR-018 §5).
 *
 * <p>Neither endpoint takes a scope from the client. The support-target list is reachable only by
 * an operational Global Support Admin, and the audit view is filtered by the caller's own role and
 * organisation inside {@link SupportAccessAuditService} — there is no scope id to tamper with.
 */
@RestController
@RequestMapping("/useradmin")
@RequiredArgsConstructor
public class SupportAccessAdminController {

  private static final int MAX_PER_PAGE = 100;

  private final @NonNull SupportTargetService supportTargetService;
  private final @NonNull SupportAccessAuditService supportAccessAuditService;

  @GetMapping("/support-targets/search")
  public ResponseEntity<Page<SupportTargetItem>> searchSupportTargets(
      @RequestParam(required = false, defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int perPage) {
    return ResponseEntity.ok(supportTargetService.search(query, pageRequest(page, perPage)));
  }

  @GetMapping("/support-access/audit")
  public ResponseEntity<Page<SupportAccessAuditItem>> supportAccessAudit(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int perPage) {
    return ResponseEntity.ok(supportAccessAuditService.find(pageRequest(page, perPage)));
  }

  private PageRequest pageRequest(int page, int perPage) {
    return PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(perPage, 1), MAX_PER_PAGE));
  }
}
