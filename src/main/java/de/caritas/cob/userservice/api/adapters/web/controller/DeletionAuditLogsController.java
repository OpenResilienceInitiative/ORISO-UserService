package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.DeletionAuditLogsService;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogEntry;
import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogsResult;
import io.swagger.annotations.Api;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API for the deletion log (who deleted what, when, with which outcome). */
@RestController
@Validated
@RequiredArgsConstructor
@Api(tags = "deletion-audit-logs-controller")
@RequestMapping({"/users/deletion-audit-logs", "/service/users/deletion-audit-logs"})
public class DeletionAuditLogsController {

  private final @NonNull DeletionAuditLogsService deletionAuditLogsService;

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
