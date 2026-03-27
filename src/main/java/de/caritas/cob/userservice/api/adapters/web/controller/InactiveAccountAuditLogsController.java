package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogEntry;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogsResult;
import io.swagger.annotations.Api;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API for Security-06 inactivity audit log entries. */
@RestController
@RequiredArgsConstructor
@Api(tags = "inactive-account-audit-logs-controller")
@RequestMapping({"/users/inactive-accounts", "/service/users/inactive-accounts"})
public class InactiveAccountAuditLogsController {

  private final @NonNull InactiveAccountAuditLogsService inactiveAccountAuditLogsService;

  @GetMapping("/audit-logs")
  public ResponseEntity<InactiveAccountAuditLogsResponseDTO> listAuditLogs(
      @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "perPage", defaultValue = "20") @Min(1) @Max(200) int perPage,
      @RequestParam(name = "accountRole", required = false) String accountRole,
      @RequestParam(name = "accountId", required = false) String accountId) {
    InactiveAccountAuditLogsResult result =
        inactiveAccountAuditLogsService.listAuditLogs(page, perPage, accountRole, accountId);
    return ResponseEntity.ok(
        new InactiveAccountAuditLogsResponseDTO(
            result.getData(), result.getTotal(), result.getPage(), result.getPerPage()));
  }

  public static class InactiveAccountAuditLogsResponseDTO {
    public final List<InactiveAccountAuditLogEntry> data;
    public final long total;
    public final int page;
    public final int perPage;

    public InactiveAccountAuditLogsResponseDTO(
        List<InactiveAccountAuditLogEntry> data, long total, int page, int perPage) {
      this.data = data;
      this.total = total;
      this.page = page;
      this.perPage = perPage;
    }
  }
}
