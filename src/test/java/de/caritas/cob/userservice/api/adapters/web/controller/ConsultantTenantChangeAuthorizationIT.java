package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_UPDATE;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantTenantChangeResultDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** #1419: only the platform admin may move a counsellor to another Träger. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class ConsultantTenantChangeAuthorizationIT {

  private static final String CONSULTANT_ID = "4c9e2a0e-8f0b-4f06-9d0a-1d6a5c0c6e11";
  private static final String ENDPOINT = "/useradmin/consultants/" + CONSULTANT_ID + "/tenant";
  private static final String BODY =
      """
      {"tenantId":8,"agencies":[{"agencyId":800,"roleSetKey":"consultant"}],"topicIds":[5]}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  @Test
  void platformAdminMovesTheConsultantAndGetsNumbersBack() throws Exception {
    when(consultantAdminFacade.changeConsultantTenant(eq(CONSULTANT_ID), any()))
        .thenReturn(
            new ConsultantTenantChangeResultDTO()
                .consultantId(CONSULTANT_ID)
                .previousTenantId(7L)
                .tenantId(8L)
                .agencyIds(List.of(800L))
                .casesLeftWithPreviousTenant(3)
                .matrixAccessRevocationsQueued(2));

    mvc.perform(request(ENDPOINT).with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantId").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.previousTenantId").value(7))
        .andExpect(jsonPath("$.tenantId").value(8))
        .andExpect(jsonPath("$.agencyIds[0]").value(800))
        .andExpect(jsonPath("$.casesLeftWithPreviousTenant").value(3))
        .andExpect(jsonPath("$.matrixAccessRevocationsQueued").value(2));

    verify(consultantAdminFacade).changeConsultantTenant(eq(CONSULTANT_ID), any());
  }

  @Test
  void theServicePrefixedPathIsGuardedTheSame() throws Exception {
    mvc.perform(request("/service" + ENDPOINT).with(adminToken(1, TENANT_ADMIN)))
        .andExpect(status().isForbidden());

    verifyNoInteractions(consultantAdminFacade);
  }

  @Test
  void aTraegerAdminWithBothAdminRolesIsForbidden() throws Exception {
    mvc.perform(request(ENDPOINT).with(adminToken(1, TENANT_ADMIN)))
        .andExpect(status().isForbidden());

    verifyNoInteractions(consultantAdminFacade);
  }

  @Test
  void everyOtherAuthorityIsForbidden() throws Exception {
    for (var authority :
        List.of(
            USER_ADMIN,
            CONSULTANT_UPDATE,
            SINGLE_TENANT_ADMIN,
            RESTRICTED_AGENCY_ADMIN,
            TECHNICAL_DEFAULT)) {
      mvc.perform(request(ENDPOINT).with(jwt().authorities(new SimpleGrantedAuthority(authority))))
          .andExpect(status().isForbidden());
    }

    verifyNoInteractions(consultantAdminFacade);
  }

  @Test
  void aRequestWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(request(ENDPOINT)).andExpect(status().isUnauthorized());

    verifyNoInteractions(consultantAdminFacade);
  }

  @Test
  void aRequestWithoutCentresIsRejectedBeforeTheFacade() throws Exception {
    mvc.perform(
            put(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":8,\"agencies\":[]}")
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-Token", "test")
                .with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(consultantAdminFacade);
  }

  /** The Admin maps these codes; the status and the header must reach it over HTTP. */
  @Test
  void everyRefusalAnswersWithItsStatusAndAStableXReasonHeader() throws Exception {
    var refusals =
        Map.of(
            HttpStatusExceptionReason.TENANT_CHANGE_MULTITENANCY_OFF, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_SAME_TENANT, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_AGENCIES_REQUIRED, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_AGENCY_UNKNOWN, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_AGENCY_OTHER_TENANT, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_TOPICS_NOT_COVERED, HttpStatus.BAD_REQUEST,
            HttpStatusExceptionReason.TENANT_CHANGE_LAST_COUNSELLOR, HttpStatus.CONFLICT,
            HttpStatusExceptionReason.TENANT_CHANGE_ADMIN_IDENTITY, HttpStatus.CONFLICT);
    for (var refusal : refusals.entrySet()) {
      when(consultantAdminFacade.changeConsultantTenant(eq(CONSULTANT_ID), any()))
          .thenThrow(new CustomValidationHttpStatusException(refusal.getKey(), refusal.getValue()));

      mvc.perform(request(ENDPOINT).with(adminToken(0, TENANT_ADMIN)))
          .andExpect(status().is(refusal.getValue().value()))
          .andExpect(header().string("X-Reason", refusal.getKey().name()));
    }
  }

  private static MockHttpServletRequestBuilder request(String path) {
    return put(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  /** Same shape as the Admin's super-admin token: both admin roles plus a tenant id claim. */
  private static JwtRequestPostProcessor adminToken(int tenantId, String authority) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of("agency-admin", "tenant-admin")))
                    .claim("tenantId", tenantId));
  }
}
