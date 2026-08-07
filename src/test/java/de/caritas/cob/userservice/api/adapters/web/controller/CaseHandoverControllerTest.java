package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogEntry;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogsResult;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CaseHandoverControllerTest {

  @Mock private CaseHandoverService caseHandoverService;
  @Mock private CaseHandoverLogsService caseHandoverLogsService;

  @InjectMocks private CaseHandoverController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void listReasons_happyPath_returnsReasonList() {
    // Business reason: clients must load allowed handover reasons from a stable endpoint.
    var reasons = List.of(CaseHandoverReason.builder().code("R1").label("Reason").build());
    when(caseHandoverService.listReasons()).thenReturn(reasons);

    var response = controller.listReasons();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(reasons, response.getBody());
  }

  @Test
  void listReasonPolicies_happyPath_returnsPolicies() {
    // Business reason: policy administration view depends on returning configured reason policies.
    var policies = List.of(CaseHandoverReason.builder().code("P1").label("Policy").build());
    when(caseHandoverService.listReasonPolicies()).thenReturn(policies);

    var response = controller.listReasonPolicies();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(policies, response.getBody());
  }

  @Test
  void updateReasonPolicies_happyPath_returnsUpdatedPolicies() {
    // Business reason: policy updates should immediately return persisted policy state.
    var input = List.of(CaseHandoverReason.builder().code("P2").label("Policy 2").build());
    var output =
        List.of(CaseHandoverReason.builder().code("P2").label("Policy 2").enabled(true).build());
    when(caseHandoverService.updateReasonPolicies(input)).thenReturn(output);

    var response = controller.updateReasonPolicies(input);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(output, response.getBody());
    verify(caseHandoverService).updateReasonPolicies(input);
  }

  @Test
  void updateReasonPolicies_validJson_deserializesAndUpdatesPolicies() throws Exception {
    var updated =
        List.of(
            CaseHandoverReason.builder()
                .code("COUNSELLOR_IS_ILL")
                .label("Illness")
                .clientConsentRequired(true)
                .accessAllowed(true)
                .enabled(true)
                .displayOrder(10)
                .policyAuthority("TENANT")
                .build());
    when(caseHandoverService.updateReasonPolicies(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(updated);

    mockMvc
        .perform(
            put("/service/users/case-handover/reason-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    [{
                      "code": "COUNSELLOR_IS_ILL",
                      "label": "Illness",
                      "clientConsentRequired": true,
                      "accessAllowed": true,
                      "enabled": true,
                      "displayOrder": 10,
                      "policyAuthority": "TENANT",
                      "clientNotificationTemplates": {"de": "Neue Beratung"}
                    }]
                    """))
        .andExpect(status().isOk());

    verify(caseHandoverService)
        .updateReasonPolicies(
            org.mockito.ArgumentMatchers.argThat(
                policies ->
                    policies.size() == 1
                        && "COUNSELLOR_IS_ILL".equals(policies.get(0).getCode())
                        && Boolean.TRUE.equals(policies.get(0).getAccessAllowed())
                        && "Neue Beratung"
                            .equals(policies.get(0).getClientNotificationTemplates().get("de"))));
  }

  @Test
  void updateReasonPolicies_malformedJson_isRejectedBeforeServiceExecution() throws Exception {
    mockMvc
        .perform(
            put("/service/users/case-handover/reason-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"code\":]"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(caseHandoverService);
  }

  @Test
  void updateReasonPolicies_requestBody_hasValidAnnotation() throws Exception {
    // Business reason: policy payload must pass bean validation before service execution.
    Method method = CaseHandoverController.class.getMethod("updateReasonPolicies", List.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Valid.class));
  }

  @Test
  void getStatus_happyPath_returnsStatusDto() {
    // Business reason: consultant clients need real-time handover status for a case.
    var status = CaseHandoverStatus.builder().sessionId(10L).status("GRANTED").build();
    when(caseHandoverService.getStatus(10L)).thenReturn(status);

    var response = controller.getStatus(10L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(status, response.getBody());
  }

  @Test
  void searchCandidates_happyPath_returnsCandidatePage() {
    // Business reason: handover search must return candidate sessions with caller pagination.
    var candidates = new ConsultantSessionListResponseDTO();
    when(caseHandoverService.searchCandidates("abc", 3, 15, true)).thenReturn(candidates);

    var response = controller.searchCandidates("abc", 3, 15, true);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(candidates, response.getBody());
  }

  @Test
  void searchCandidates_parameters_haveMinAndMaxValidationAnnotations() throws Exception {
    // Business reason: endpoint-level bounds keep candidate queries safe and predictable.
    Method method =
        CaseHandoverController.class.getMethod(
            "searchCandidates", String.class, int.class, int.class, boolean.class);

    assertTrue(method.getParameters()[1].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[2].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[2].isAnnotationPresent(Max.class));
  }

  @Test
  void requestAccess_happyPath_returnsCreatedAndDelegates() {
    // Business reason: creating a handover request must persist and return the new status snapshot.
    var request = new CaseHandoverController.CaseHandoverRequestDTO();
    request.setReasonCode("COUNSELLOR_ON_HOLIDAY");
    request.setExplanation("handover please");
    var status = CaseHandoverStatus.builder().sessionId(11L).status("PENDING").build();
    when(caseHandoverService.requestAccess(11L, "COUNSELLOR_ON_HOLIDAY", "handover please"))
        .thenReturn(status);

    var response = controller.requestAccess(11L, request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals(status, response.getBody());
  }

  @Test
  void requestAccess_reasonCodeField_hasNotBlankAnnotation() throws Exception {
    // Business reason: reasons are mandatory for auditing why case handover was requested.
    Field field =
        CaseHandoverController.CaseHandoverRequestDTO.class.getDeclaredField("reasonCode");

    assertTrue(field.isAnnotationPresent(NotBlank.class));
  }

  @Test
  void requestAccess_explanationField_hasNotBlankAnnotation() throws Exception {
    // Business reason: explanation text is required to support case accountability.
    Field field =
        CaseHandoverController.CaseHandoverRequestDTO.class.getDeclaredField("explanation");

    assertTrue(field.isAnnotationPresent(NotBlank.class));
  }

  @Test
  void decideClientConsent_approvedTrue_delegatesWithTrue() {
    // Business reason: consent approval must explicitly grant handover via true flag.
    var decision = new CaseHandoverController.ClientConsentDecisionDTO();
    decision.setApproved(true);
    var status = CaseHandoverStatus.builder().status("GRANTED").build();
    when(caseHandoverService.resolveClientConsent(12L, 20L, true)).thenReturn(status);

    var response = controller.decideClientConsent(12L, 20L, decision);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(status, response.getBody());
    verify(caseHandoverService).resolveClientConsent(12L, 20L, true);
  }

  @Test
  void decideClientConsent_approvedFalse_delegatesWithFalse() {
    // Business reason: consent decline must be propagated as false without ambiguity.
    var decision = new CaseHandoverController.ClientConsentDecisionDTO();
    decision.setApproved(false);
    var status = CaseHandoverStatus.builder().status("DECLINED").build();
    when(caseHandoverService.resolveClientConsent(12L, 21L, false)).thenReturn(status);

    var response = controller.decideClientConsent(12L, 21L, decision);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(status, response.getBody());
    verify(caseHandoverService).resolveClientConsent(12L, 21L, false);
  }

  @Test
  void decideClientConsent_approvedField_hasNotNullAnnotation() throws Exception {
    // Business reason: explicit client decision is required before finalizing handover requests.
    Field field =
        CaseHandoverController.ClientConsentDecisionDTO.class.getDeclaredField("approved");

    assertTrue(field.isAnnotationPresent(NotNull.class));
  }

  @Test
  void requestBatchAccess_emptySessionIdsField_hasNotEmptyAnnotation() throws Exception {
    // Business reason: batch handover calls must reject empty targets to avoid no-op writes.
    Field field =
        CaseHandoverController.CaseHandoverBatchRequestDTO.class.getDeclaredField("sessionIds");

    assertTrue(field.isAnnotationPresent(NotEmpty.class));
  }

  @Test
  void requestBatchAccess_duplicateSessionIds_deduplicatesBeforeDelegation() {
    // Business reason: duplicate session IDs should not produce duplicate handover requests.
    var request = new CaseHandoverController.CaseHandoverBatchRequestDTO();
    request.setReasonCode("COUNSELLOR_ON_HOLIDAY");
    request.setExplanation("batch");
    request.setSessionIds(List.of(100L, 100L, 200L));
    var status = CaseHandoverStatus.builder().status("PENDING").build();
    when(caseHandoverService.requestAccess(100L, "COUNSELLOR_ON_HOLIDAY", "batch"))
        .thenReturn(status);
    when(caseHandoverService.requestAccess(200L, "COUNSELLOR_ON_HOLIDAY", "batch"))
        .thenReturn(status);

    var response = controller.requestBatchAccess(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(2, response.getBody().size());
    verify(caseHandoverService, times(1)).requestAccess(100L, "COUNSELLOR_ON_HOLIDAY", "batch");
    verify(caseHandoverService, times(1)).requestAccess(200L, "COUNSELLOR_ON_HOLIDAY", "batch");
  }

  @Test
  void requestBatchAccess_mixedSuccessFailure_populatesErrorForFailedEntry() {
    // Business reason: batch responses must expose per-session errors so UI can retry selectively.
    var request = new CaseHandoverController.CaseHandoverBatchRequestDTO();
    request.setReasonCode("COUNSELLOR_ON_HOLIDAY");
    request.setExplanation("batch");
    request.setSessionIds(List.of(300L, 301L));
    var status = CaseHandoverStatus.builder().status("PENDING").build();
    when(caseHandoverService.requestAccess(300L, "COUNSELLOR_ON_HOLIDAY", "batch"))
        .thenReturn(status);
    when(caseHandoverService.requestAccess(301L, "COUNSELLOR_ON_HOLIDAY", "batch"))
        .thenThrow(new RuntimeException("no agency access"));

    var response = controller.requestBatchAccess(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(2, response.getBody().size());
    assertTrue(response.getBody().get(0).success);
    assertFalse(response.getBody().get(1).success);
    assertEquals("no agency access", response.getBody().get(1).error);
  }

  @Test
  void listLogs_happyPath_paginationDelegatesAndReturnsResponseDto() {
    // Business reason: audit log pages need stable totals and paging metadata for operations teams.
    var result =
        CaseHandoverLogsResult.builder()
            .data(List.of(CaseHandoverLogEntry.builder().requestId(7L).sessionId(8L).build()))
            .total(42L)
            .page(2)
            .perPage(10)
            .build();
    when(caseHandoverLogsService.listCaseHandoverLogs(2, 10)).thenReturn(result);

    var response = controller.listLogs(2, 10);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(42L, response.getBody().total);
    assertEquals(2, response.getBody().page);
    assertEquals(10, response.getBody().perPage);
    verify(caseHandoverLogsService).listCaseHandoverLogs(2, 10);
  }

  @Test
  void listLogs_parameters_haveMinAnnotations() throws Exception {
    // Business reason: page and page size constraints prevent invalid pagination requests.
    Method method = CaseHandoverController.class.getMethod("listLogs", int.class, int.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[1].isAnnotationPresent(Min.class));
  }
}
