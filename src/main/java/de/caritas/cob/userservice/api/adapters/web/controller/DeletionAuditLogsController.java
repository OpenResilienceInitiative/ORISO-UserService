package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogEntry;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogsResult;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API for the deletion log (who deleted what, when, with which outcome): the platform-admin read
 * side, and the write side AgencyService and TenantService report their deletions to.
 */
@RestController
@Validated
@RequiredArgsConstructor
@Api(tags = "deletion-audit-logs-controller")
@RequestMapping({"/users/deletion-audit-logs", "/service/users/deletion-audit-logs"})
public class DeletionAuditLogsController {

  private final @NonNull DeletionAuditLogsService deletionAuditLogsService;
  private final @NonNull DeletionAuditService deletionAuditService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AdminScope adminScope;

  /**
   * Records an agency or tenant deletion. Best effort: the write never reports a failure back, so
   * the calling service's deletion is never held up by it. The technical account (scheduler, no
   * user) names the actor itself. An admin who forwards its own token is recorded as that admin,
   * whatever the body says, and only for its own tenant unless it is a platform admin.
   */
  @PostMapping
  public ResponseEntity<Void> recordDeletion(
      @Valid @RequestBody DeletionAuditEntryRequestDTO request) {
    if (request.getTargetType() != DeletionAuditTargetType.AGENCY
        && request.getTargetType() != DeletionAuditTargetType.TENANT) {
      return ResponseEntity.badRequest().build();
    }
    var authorities = authenticatedUser.getGrantedAuthorities();
    if (authorities != null && authorities.contains(AuthorityValue.TECHNICAL_DEFAULT)) {
      // Actor and role come as a pair: a role without a person would read as a person's action
      // that names nobody, and a person without a role would be recorded as SYSTEM.
      if ((request.getActorRole() == null) != (request.getActorId() == null)) {
        return ResponseEntity.badRequest().build();
      }
      if (request.getActorRole() == null) {
        deletionAuditService.recordAsSystem(
            request.getTargetType(),
            request.getTargetId(),
            request.getTenantId(),
            request.getOutcome());
      } else {
        deletionAuditService.recordReported(
            request.getTargetType(),
            request.getTargetId(),
            request.getTenantId(),
            request.getOutcome(),
            request.getActorId(),
            request.getActorRole());
      }
    } else {
      // A partly failed purge is a finding of the deletion job, never something a person reports.
      if (request.getOutcome() == DeletionAuditOutcome.PARTIAL_FAILURE) {
        return ResponseEntity.badRequest().build();
      }
      // A Beratungsstelle is purged by the nightly job, which reports EXECUTED through the
      // AgencyService outbox. A person's EXECUTED row would make that pull skip the real one.
      if (request.getTargetType() == DeletionAuditTargetType.AGENCY
          && request.getOutcome() == DeletionAuditOutcome.EXECUTED) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
      if (!authenticatedUser.isPlatformAdmin()
          && (request.getTenantId() == null
              || !request.getTenantId().equals(authenticatedUser.getTenantId()))) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
      // The target itself must be within the caller's reach too, not only the tenant: a Träger
      // admin reports only its own Träger, an agency admin only an agency it administers.
      Long targetId = Long.valueOf(request.getTargetId());
      adminScope.assertMay(
          request.getTargetType() == DeletionAuditTargetType.TENANT
              ? Target.tenant(targetId)
              : Target.agencies(List.of(targetId)));
      deletionAuditService.recordAsCurrentActor(
          request.getTargetType(),
          request.getTargetId(),
          request.getTenantId(),
          request.getOutcome());
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  public ResponseEntity<DeletionAuditLogsResponseDTO> listAuditLogs(
      @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "perPage", defaultValue = "20") @Min(1) @Max(200) int perPage,
      @RequestParam(name = "targetType", required = false) String targetType,
      @RequestParam(name = "outcome", required = false) String outcome,
      @RequestParam(name = "targetId", required = false) String targetId) {
    DeletionAuditLogsResult result =
        deletionAuditLogsService.listAuditLogs(page, perPage, targetType, outcome, targetId);
    return ResponseEntity.ok(
        new DeletionAuditLogsResponseDTO(
            result.getData(), result.getTotal(), result.getPage(), result.getPerPage()));
  }

  public static class DeletionAuditLogsResponseDTO {
    public final List<DeletionAuditLogEntry> data;
    public final long total;
    public final int page;
    public final int perPage;

    public DeletionAuditLogsResponseDTO(
        List<DeletionAuditLogEntry> data, long total, int page, int perPage) {
      this.data = data;
      this.total = total;
      this.page = page;
      this.perPage = perPage;
    }
  }
}
