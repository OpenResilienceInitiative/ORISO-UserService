package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.CaseHandoverPolicyCacheService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.BooleanPermissionPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** #1536: the reason picker must only ever offer the four neutral reason codes. */
@SpringBootTest(properties = "multitenancy.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class CaseHandoverReasonsEndpointIT {

  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;
  @Autowired private MockMvc mvc;
  @MockitoBean CaseHandoverPolicyCacheService policies;
  @MockitoBean de.caritas.cob.userservice.api.service.user.UserAccountService accounts;
  private CaseHandoverPolicies reasons;

  @BeforeEach
  void resolvedTenantPolicy() {
    TenantContext.clear();
    when(accounts.retrieveValidatedConsultant())
        .thenReturn(
            de.caritas.cob.userservice.api.model.Consultant.builder()
                .id("stored-consultant")
                .username("stored-consultant")
                .firstName("Stored")
                .lastName("Consultant")
                .email("stored@example.org")
                .tenantId(7L)
                .build());
    var values = new LinkedHashMap<String, CaseHandoverReasonPolicy>();
    for (var code :
        List.of(
            "COUNSELLOR_ASKED_FOR_ADVICE",
            "COUNSELLOR_ON_HOLIDAY",
            "COUNSELLOR_IS_ILL",
            "COUNSELLOR_LEFT")) {
      values.put(
          code,
          new CaseHandoverReasonPolicy()
              .code(CaseHandoverReasonPolicy.CodeEnum.fromValue(code))
              .labels(
                  new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                          .MultilingualTextPermissionPolicy(null)
                      .value(java.util.Map.of("de", "Neutrale Bezeichnung"))
                      .mode(PermissionPolicyMode.ENFORCED))
              .clientConsentRequired(
                  new BooleanPermissionPolicy(null)
                      .value(false)
                      .mode(PermissionPolicyMode.SUGGESTED))
              .approvalRoles(
                  new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                          .StringListPermissionPolicy(null)
                      .value(java.util.Set.of())
                      .mode(PermissionPolicyMode.ENFORCED))
              .clientNotificationTemplates(
                  new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                          .MultilingualTextPermissionPolicy(null)
                      .value(java.util.Map.of())
                      .mode(PermissionPolicyMode.SUGGESTED))
              .maxAccessDurationMinutes(
                  code.equals("COUNSELLOR_ASKED_FOR_ADVICE")
                      ? new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                              .IntegerPermissionPolicy(null)
                          .value(180)
                          .mode(PermissionPolicyMode.ENFORCED)
                      : null)
              .enabled(
                  new BooleanPermissionPolicy(null).value(true).mode(PermissionPolicyMode.ENFORCED))
              .accessAllowed(
                  new BooleanPermissionPolicy(null)
                      .value(true)
                      .mode(PermissionPolicyMode.ENFORCED)));
    }
    reasons = new CaseHandoverPolicies().reasons(values);
    when(policies.getCurrent(7L))
        .thenReturn(new CaseHandoverPolicyCacheService.CurrentPolicy(true, reasons));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void disabledMasterReturnsNoAvailableReasonsDespiteEnabledPreferences() throws Exception {
    when(policies.getCurrent(7L))
        .thenReturn(new CaseHandoverPolicyCacheService.CurrentPolicy(false, reasons));
    mvc.perform(get("/users/case-handover/reasons").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void getReasonsServesTheFourNeutralCodesAndNoHealthStatement() throws Exception {
    mvc.perform(get("/users/case-handover/reasons").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].code")
                .value(
                    contains(
                        "ADVICE_REQUESTED",
                        "PLANNED_ABSENCE",
                        "UNPLANNED_ABSENCE",
                        "ASSIGNMENT_ENDED")))
        .andExpect(content().string(not(containsStringIgnoringCase("COUNSELLOR_IS_ILL"))))
        .andExpect(content().string(not(containsStringIgnoringCase("erkrankt"))))
        .andExpect(content().string(not(containsStringIgnoringCase(" is ill"))));
  }
}
