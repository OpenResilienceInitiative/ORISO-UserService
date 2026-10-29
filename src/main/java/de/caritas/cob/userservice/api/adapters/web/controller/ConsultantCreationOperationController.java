package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.*;
import de.caritas.cob.userservice.api.admin.service.consultant.create.AccountCreationAttemptJournal;
import de.caritas.cob.userservice.api.service.creationoutcome.*;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Receipt;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Receipt boundary delegated by the generated useradmin HTTP contract. */
@Component
@RequiredArgsConstructor
public class ConsultantCreationOperationController {
  private final AccountCreationAttemptJournal journal;
  private final CreationOperationAccess access;

  public ResponseEntity<ConsultantCreationOperationDTO> getConsultantCreationOperation(UUID id) {
    access.requireTrackedCaller();
    return response(
        TrackedConsultantCreation.journalRead(() -> journal.findById(id))
            .orElseThrow(CreationOperationAccess::denied));
  }

  public ResponseEntity<ConsultantCreationOperationDTO> getConsultantCreationOperationByKey(
      UUID key) {
    access.requireTrackedCaller();
    return response(
        TrackedConsultantCreation.journalRead(() -> journal.findByKey(key))
            .orElseThrow(CreationOperationAccess::denied));
  }

  private ResponseEntity<ConsultantCreationOperationDTO> response(Receipt r) {
    access.requireReceiptAccess(r);
    var dto =
        new ConsultantCreationOperationDTO()
            .operationId(r.operationId())
            .nativeId(r.nativeId())
            .version(r.version())
            .scope(
                new ConsultantCreationScopeDTO()
                    .tenantId(r.scope().tenantId())
                    .agencyIds(r.scope().agencyIds())
                    .role(ConsultantCreationScopeDTO.RoleEnum.COUNSELLOR))
            .dispatch(ConsultantCreationOperationDTO.DispatchEnum.valueOf(r.dispatch().name()))
            .workflow(ConsultantCreationOperationDTO.WorkflowEnum.valueOf(r.workflow().name()))
            .row(ConsultantCreationOperationDTO.RowEnum.valueOf(r.row().name()))
            .onboarding(
                ConsultantCreationOperationDTO.OnboardingEnum.valueOf(r.onboarding().name()))
            .compensation(
                ConsultantCreationOperationDTO.CompensationEnum.valueOf(r.compensation().name()))
            .reason(ConsultantCreationOperationDTO.ReasonEnum.valueOf(r.reason().name()))
            .createdAt(r.createdAt().atOffset(ZoneOffset.UTC))
            .updatedAt(r.updatedAt().atOffset(ZoneOffset.UTC))
            .dispatchedAt(
                r.dispatchedAt() == null ? null : r.dispatchedAt().atOffset(ZoneOffset.UTC))
            .capturedAt(r.capturedAt() == null ? null : r.capturedAt().atOffset(ZoneOffset.UTC));
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header(
            CreationOperationAccess.RECEIPT_HEADER,
            CreationOperationAccess.receiptUri(r.operationId()))
        .body(dto);
  }
}
