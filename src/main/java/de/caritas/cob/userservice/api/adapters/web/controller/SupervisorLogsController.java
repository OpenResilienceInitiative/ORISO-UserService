package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.SupervisorLogsService;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogEntry;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogsResult;
import io.swagger.annotations.Api;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Api(tags = "supervisor-logs-controller")
@RequestMapping({"/users/supervisors", "/service/users/supervisors"})
public class SupervisorLogsController {

  private final @NonNull SupervisorLogsService supervisorLogsService;

  @GetMapping("/logs")
  public ResponseEntity<SupervisorLogsResponseDTO> listSupervisorLogs(
      @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "perPage", defaultValue = "20") @Min(1) @Max(200) int perPage) {
    SupervisorLogsResult result = supervisorLogsService.listSupervisorLogs(page, perPage);
    return ResponseEntity.ok(
        new SupervisorLogsResponseDTO(result.getData(), result.getTotal(), result.getPage(), result.getPerPage()));
  }

  public static class SupervisorLogsResponseDTO {
    public final java.util.List<SupervisorLogEntry> data;
    public final long total;
    public final int page;
    public final int perPage;

    public SupervisorLogsResponseDTO(
        java.util.List<SupervisorLogEntry> data, long total, int page, int perPage) {
      this.data = data;
      this.total = total;
      this.page = page;
      this.perPage = perPage;
    }
  }
}


