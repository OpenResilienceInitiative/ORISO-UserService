package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.CaseHandoverLogsService;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogEntry;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogsResult;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import io.swagger.annotations.Api;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Api(tags = "case-handover-controller")
public class CaseHandoverController {

  private final @NonNull CaseHandoverService caseHandoverService;
  private final @NonNull CaseHandoverLogsService caseHandoverLogsService;

  @GetMapping({"/users/case-handover/reasons", "/service/users/case-handover/reasons"})
  public ResponseEntity<List<CaseHandoverReason>> listReasons() {
    return ResponseEntity.ok(caseHandoverService.listReasons());
  }

  @GetMapping({
    "/users/case-handover/reason-policies",
    "/service/users/case-handover/reason-policies"
  })
  public ResponseEntity<List<CaseHandoverReason>> listReasonPolicies() {
    return ResponseEntity.ok(caseHandoverService.listReasonPolicies());
  }

  @PutMapping({
    "/users/case-handover/reason-policies",
    "/service/users/case-handover/reason-policies"
  })
  public ResponseEntity<List<CaseHandoverReason>> updateReasonPolicies(
      @Valid @RequestBody List<CaseHandoverReason> policies) {
    return ResponseEntity.ok(caseHandoverService.updateReasonPolicies(policies));
  }

  @GetMapping({
    "/users/sessions/{sessionId}/case-handover",
    "/service/users/sessions/{sessionId}/case-handover"
  })
  public ResponseEntity<CaseHandoverStatus> getStatus(@PathVariable Long sessionId) {
    return ResponseEntity.ok(caseHandoverService.getStatus(sessionId));
  }

  @PostMapping({
    "/users/sessions/{sessionId}/case-handover",
    "/service/users/sessions/{sessionId}/case-handover"
  })
  public ResponseEntity<CaseHandoverStatus> requestAccess(
      @PathVariable Long sessionId, @Valid @RequestBody CaseHandoverRequestDTO request) {
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(
            sessionId, request.getReasonCode(), request.getExplanation());
    return ResponseEntity.status(HttpStatus.CREATED).body(status);
  }

  @PostMapping({
    "/users/sessions/{sessionId}/case-handover/{requestId}/client-consent",
    "/service/users/sessions/{sessionId}/case-handover/{requestId}/client-consent"
  })
  public ResponseEntity<CaseHandoverStatus> decideClientConsent(
      @PathVariable Long sessionId,
      @PathVariable Long requestId,
      @Valid @RequestBody ClientConsentDecisionDTO decision) {
    return ResponseEntity.ok(
        caseHandoverService.resolveClientConsent(
            sessionId, requestId, Boolean.TRUE.equals(decision.getApproved())));
  }

  @PostMapping({"/users/case-handover/batch", "/service/users/case-handover/batch"})
  public ResponseEntity<List<CaseHandoverBatchResultDTO>> requestBatchAccess(
      @Valid @RequestBody CaseHandoverBatchRequestDTO request) {
    List<CaseHandoverBatchResultDTO> results = new ArrayList<>();
    request.getSessionIds().stream()
        .distinct()
        .forEach(
            sessionId -> {
              try {
                results.add(
                    CaseHandoverBatchResultDTO.success(
                        sessionId,
                        caseHandoverService.requestAccess(
                            sessionId, request.getReasonCode(), request.getExplanation())));
              } catch (RuntimeException exception) {
                results.add(CaseHandoverBatchResultDTO.failure(sessionId, exception.getMessage()));
              }
            });
    return ResponseEntity.status(HttpStatus.CREATED).body(results);
  }

  @GetMapping({"/users/case-handover/logs", "/service/users/case-handover/logs"})
  public ResponseEntity<CaseHandoverLogsResponseDTO> listLogs(
      @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "perPage", defaultValue = "20") @Min(1) @Max(200) int perPage) {
    CaseHandoverLogsResult result = caseHandoverLogsService.listCaseHandoverLogs(page, perPage);
    return ResponseEntity.ok(
        new CaseHandoverLogsResponseDTO(
            result.getData(), result.getTotal(), result.getPage(), result.getPerPage()));
  }

  public static class CaseHandoverRequestDTO {
    @NotBlank private String reasonCode;
    @NotBlank private String explanation;

    public String getReasonCode() {
      return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
      this.reasonCode = reasonCode;
    }

    public String getExplanation() {
      return explanation;
    }

    public void setExplanation(String explanation) {
      this.explanation = explanation;
    }
  }

  public static class CaseHandoverBatchRequestDTO extends CaseHandoverRequestDTO {
    @NotEmpty private List<Long> sessionIds;

    public List<Long> getSessionIds() {
      return sessionIds;
    }

    public void setSessionIds(List<Long> sessionIds) {
      this.sessionIds = sessionIds;
    }
  }

  public static class ClientConsentDecisionDTO {
    @NotNull private Boolean approved;

    public Boolean getApproved() {
      return approved;
    }

    public void setApproved(Boolean approved) {
      this.approved = approved;
    }
  }

  public static class CaseHandoverBatchResultDTO {
    public final Long sessionId;
    public final boolean success;
    public final CaseHandoverStatus status;
    public final String error;

    private CaseHandoverBatchResultDTO(
        Long sessionId, boolean success, CaseHandoverStatus status, String error) {
      this.sessionId = sessionId;
      this.success = success;
      this.status = status;
      this.error = error;
    }

    public static CaseHandoverBatchResultDTO success(Long sessionId, CaseHandoverStatus status) {
      return new CaseHandoverBatchResultDTO(sessionId, true, status, null);
    }

    public static CaseHandoverBatchResultDTO failure(Long sessionId, String error) {
      return new CaseHandoverBatchResultDTO(sessionId, false, null, error);
    }
  }

  public static class CaseHandoverLogsResponseDTO {
    public final List<CaseHandoverLogEntry> data;
    public final long total;
    public final int page;
    public final int perPage;

    public CaseHandoverLogsResponseDTO(
        List<CaseHandoverLogEntry> data, long total, int page, int perPage) {
      this.data = data;
      this.total = total;
      this.page = page;
      this.perPage = perPage;
    }
  }
}
