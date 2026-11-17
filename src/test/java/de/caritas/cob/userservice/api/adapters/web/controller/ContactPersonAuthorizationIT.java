package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactPersonAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TenantContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TraegerDefaultContactDTO;
import de.caritas.cob.userservice.api.admin.service.contactperson.ContactPersonAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Route-level access to the contact-person endpoints (ORISO-Admin#1534). The scope within a role is
 * decided in ContactPersonAdminService and AgencyAdminContactService.
 */
@TestPropertySource(properties = "spring.profiles.active=testing")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class ContactPersonAuthorizationIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final String AGENCY_PATH = "/useradmin/agencies/7/contactperson";
  private static final String TENANT_PATH = "/useradmin/tenants/3/contactperson";
  private static final String COVERAGE_PATH = "/useradmin/agencycontacts/coverage";
  private static final String COUNSELLOR_PATH = "/users/agencies/999999/contact-person";

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private ContactPersonAdminService contactPersonAdminService;

  @Autowired private MockMvc mvc;

  @Test
  void adminEndpoints_Should_answerUnauthorized_When_noTokenIsPresent() throws Exception {
    mvc.perform(csrf(get(AGENCY_PATH))).andExpect(status().isUnauthorized());
    mvc.perform(csrf(get(TENANT_PATH))).andExpect(status().isUnauthorized());
    verifyNoInteractions(contactPersonAdminService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
  void adminEndpoints_Should_answerForbidden_When_theCallerIsACounsellor() throws Exception {
    mvc.perform(csrf(get(AGENCY_PATH))).andExpect(status().isForbidden());
    mvc.perform(csrf(get(AGENCY_PATH + "/candidates"))).andExpect(status().isForbidden());
    mvc.perform(csrf(get(TENANT_PATH))).andExpect(status().isForbidden());
    mvc.perform(json(post(COVERAGE_PATH), "{\"agencyIds\":[7]}")).andExpect(status().isForbidden());
    verifyNoInteractions(contactPersonAdminService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.RESTRICTED_AGENCY_ADMIN})
  void agencyAdmin_Should_reachTheCentreEndpoints_But_notTheTraegerDefault() throws Exception {
    when(contactPersonAdminService.getAgencyContactPerson(7L, null)).thenReturn(centre());
    when(contactPersonAdminService.getAgencyContactPersonCandidates(7L, null))
        .thenReturn(List.of());
    when(contactPersonAdminService.getAgencyContactCoverage(any())).thenReturn(List.of());

    mvc.perform(csrf(get(AGENCY_PATH))).andExpect(status().isOk());
    mvc.perform(csrf(get(AGENCY_PATH + "/candidates"))).andExpect(status().isOk());
    mvc.perform(json(post(COVERAGE_PATH), "{\"agencyIds\":[7]}")).andExpect(status().isOk());
    mvc.perform(csrf(get(TENANT_PATH))).andExpect(status().isForbidden());
    mvc.perform(json(put(TENANT_PATH), "{}")).andExpect(status().isForbidden());
    mvc.perform(csrf(get(TENANT_PATH + "/candidates"))).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void traegerAdmin_Should_reachCentreAndTraegerEndpoints() throws Exception {
    when(contactPersonAdminService.updateAgencyContactPerson(eq(7L), eq(null), any()))
        .thenReturn(centre());
    when(contactPersonAdminService.updateTenantContactPerson(eq(3L), any()))
        .thenReturn(new TenantContactPersonDTO().tenantId(3L));

    mvc.perform(json(put(AGENCY_PATH), "{\"contactAdminId\":\"a-1\"}")).andExpect(status().isOk());
    mvc.perform(json(put(TENANT_PATH), "{\"generalContactEmail\":\"info@traeger.org\"}"))
        .andExpect(status().isOk());
    verify(contactPersonAdminService).updateTenantContactPerson(eq(3L), any());
  }

  @Test
  void counsellorEndpoint_Should_answerUnauthorized_When_noTokenIsPresent() throws Exception {
    mvc.perform(get(COUNSELLOR_PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  void counsellorEndpoint_Should_answerForbidden_When_theCallerIsAnAdviceSeeker() throws Exception {
    mvc.perform(
            get(COUNSELLOR_PATH)
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT))
                        .jwt(token -> token.claim("userId", "asker-1"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void counsellorEndpoint_Should_answerForbidden_When_theCallerIsNoCounsellorOfTheCentre()
      throws Exception {
    mvc.perform(
            get(COUNSELLOR_PATH)
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                        .jwt(token -> token.claim("userId", "consultant-1"))))
        .andExpect(status().isForbidden());
  }

  private static AgencyContactPersonAdminDTO centre() {
    return new AgencyContactPersonAdminDTO()
        .agencyId(7L)
        .traegerDefault(new TraegerDefaultContactDTO())
        .effectiveSource(AgencyContactPersonAdminDTO.EffectiveSourceEnum.NONE);
  }

  private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request.cookie(CSRF_COOKIE).header(CSRF_HEADER, CSRF_VALUE);
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return csrf(request).contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
