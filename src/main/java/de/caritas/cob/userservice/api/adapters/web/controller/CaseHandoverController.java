package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogEntry;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogsResult;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

  @GetMapping({
    "/users/sessions/{sessionId}/case-handover/{requestId}",
    "/service/users/sessions/{sessionId}/case-handover/{requestId}"
  })
  public ResponseEntity<CaseHandoverStatus> getRequestStatus(
      @PathVariable Long sessionId, @PathVariable Long requestId) {
    return ResponseEntity.ok(caseHandoverService.getRequestStatus(sessionId, requestId));
  }

  @GetMapping({"/users/case-handover/candidates", "/service/users/case-handover/candidates"})
  public ResponseEntity<ConsultantSessionListResponseDTO> searchCandidates(
      @RequestParam(name = "query", defaultValue = "") String query,
      @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
      @RequestParam(name = "count", defaultValue = "15") @Min(1) @Max(200) int count,
      @RequestParam(name = "archived", defaultValue = "false") boolean archived) {
    return ResponseEntity.ok(caseHandoverService.searchCandidates(query, offset, count, archived));
  }

  @PostMapping({
    "/users/sessions/{sessionId}/case-handover",
    "/service/users/sessions/{sessionId}/case-handover"
  })
  public ResponseEntity<CaseHandoverStatus> requestAccess(
      @PathVariable Long sessionId, @Valid @RequestBody CaseHandoverRequestDTO request) {
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(
            sessionId,
            request.getReasonCode(),
            request.getExplanation(),
            request.getExpectedOwnershipRevision(),
            request.getOperationId());
    return ResponseEntity.status(HttpStatus.CREATED).body(status);
  }

  @PostMapping({
    "/users/sessions/{sessionId}/case-handover/offers",
    "/service/users/sessions/{sessionId}/case-handover/offers"
  })
  public ResponseEntity<CaseHandoverStatus> createOffer(
      @PathVariable Long sessionId, @Valid @RequestBody CaseHandoverOfferDTO offer) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            caseHandoverService.createOffer(
                sessionId,
                offer.getTargetConsultantId(),
                offer.getReasonCode(),
                offer.getExplanation(),
                offer.getExpectedOwnershipRevision(),
                offer.getOperationId()));
  }

  @PostMapping({
    "/users/sessions/{sessionId}/case-handover/{requestId}/recipient-decision",
    "/service/users/sessions/{sessionId}/case-handover/{requestId}/recipient-decision"
  })
  public ResponseEntity<CaseHandoverStatus> decideRecipientOffer(
      @PathVariable Long sessionId,
      @PathVariable Long requestId,
      @Valid @RequestBody RecipientDecisionDTO decision) {
    return ResponseEntity.ok(
        caseHandoverService.resolveRecipientDecision(
            sessionId, requestId, Boolean.TRUE.equals(decision.getApproved())));
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
    request.getOperations().stream()
        .forEach(
            operation -> {
              try {
                results.add(
                    CaseHandoverBatchResultDTO.success(
                        operation.getSessionId(),
                        operation.getOperationId(),
                        caseHandoverService.requestAccess(
                            operation.getSessionId(),
                            request.getReasonCode(),
                            request.getExplanation(),
                            operation.getExpectedOwnershipRevision(),
                            operation.getOperationId())));
              } catch (RuntimeException exception) {
                results.add(
                    CaseHandoverBatchResultDTO.failure(
                        operation.getSessionId(),
                        operation.getOperationId(),
                        exception.getMessage()));
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

    @NotNull
    @Min(0)
    private Long expectedOwnershipRevision;

    @NotNull private UUID operationId;

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

    public Long getExpectedOwnershipRevision() {
      return expectedOwnershipRevision;
    }

    public void setExpectedOwnershipRevision(Long expectedOwnershipRevision) {
      this.expectedOwnershipRevision = expectedOwnershipRevision;
    }

    public UUID getOperationId() {
      return operationId;
    }

    public void setOperationId(UUID operationId) {
      this.operationId = operationId;
    }
  }

  public static class CaseHandoverOfferDTO {
    @NotBlank private String targetConsultantId;
    @NotBlank private String reasonCode;
    private String explanation;

    @NotNull
    @Min(0)
    private Long expectedOwnershipRevision;

    @NotNull private UUID operationId;

    public String getTargetConsultantId() {
      return targetConsultantId;
    }

    public void setTargetConsultantId(String targetConsultantId) {
      this.targetConsultantId = targetConsultantId;
    }

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

    public Long getExpectedOwnershipRevision() {
      return expectedOwnershipRevision;
    }

    public void setExpectedOwnershipRevision(Long expectedOwnershipRevision) {
      this.expectedOwnershipRevision = expectedOwnershipRevision;
    }

    public UUID getOperationId() {
      return operationId;
    }

    public void setOperationId(UUID operationId) {
      this.operationId = operationId;
    }
  }

  public static class CaseHandoverBatchRequestDTO {
    @NotBlank private String reasonCode;
    @NotBlank private String explanation;
    @NotEmpty @Valid private List<@NotNull CaseHandoverBatchOperationDTO> operations;

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

    public List<CaseHandoverBatchOperationDTO> getOperations() {
      return operations;
    }

    public void setOperations(List<CaseHandoverBatchOperationDTO> operations) {
      this.operations = operations;
    }
  }

  public static class CaseHandoverBatchOperationDTO {
    @NotNull private Long sessionId;

    @NotNull
    @Min(0)
    private Long expectedOwnershipRevision;

    @NotNull private UUID operationId;

    public Long getSessionId() {
      return sessionId;
    }

    public void setSessionId(Long sessionId) {
      this.sessionId = sessionId;
    }

    public Long getExpectedOwnershipRevision() {
      return expectedOwnershipRevision;
    }

    public void setExpectedOwnershipRevision(Long expectedOwnershipRevision) {
      this.expectedOwnershipRevision = expectedOwnershipRevision;
    }

    public UUID getOperationId() {
      return operationId;
    }

    public void setOperationId(UUID operationId) {
      this.operationId = operationId;
    }
  }

  public static class RecipientDecisionDTO {
    @NotNull private Boolean approved;

    public Boolean getApproved() {
      return approved;
    }

    public void setApproved(Boolean approved) {
      this.approved = approved;
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
    public final UUID operationId;
    public final boolean success;
    public final CaseHandoverStatus status;
    public final String error;

    private CaseHandoverBatchResultDTO(
        Long sessionId,
        UUID operationId,
        boolean success,
        CaseHandoverStatus status,
        String error) {
      this.sessionId = sessionId;
      this.operationId = operationId;
      this.success = success;
      this.status = status;
      this.error = error;
    }

    public static CaseHandoverBatchResultDTO success(
        Long sessionId, UUID operationId, CaseHandoverStatus status) {
      return new CaseHandoverBatchResultDTO(sessionId, operationId, true, status, null);
    }

    public static CaseHandoverBatchResultDTO failure(
        Long sessionId, UUID operationId, String error) {
      return new CaseHandoverBatchResultDTO(sessionId, operationId, false, null, error);
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
