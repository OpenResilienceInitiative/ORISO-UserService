package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "spring.profiles.active=testing")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class ErstantwortDefaultsControllerIT {

  private static final String PATH = "/useradmin/erstantwort/defaults";

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @Autowired private MockMvc mvc;

  @Test
  void anonymousGets401() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
  void nonAdminGets403() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void tenantAdminGetsFormalGermanByDefault() throws Exception {
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locale").value("de"))
        .andExpect(jsonPath("$.greeting").value(org.hamcrest.Matchers.containsString("Sie")))
        .andExpect(
            jsonPath("$.emergencyNumbers").value(org.hamcrest.Matchers.containsString("112")))
        .andExpect(jsonPath("$.responseDeadlineDays").value(2));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.RESTRICTED_AGENCY_ADMIN})
  void agencyAdminGetsInformalGermanAndEnglish() throws Exception {
    mvc.perform(get(PATH).param("informal", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locale").value("de@informal"));
    mvc.perform(get(PATH).param("locale", "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locale").value("en"))
        .andExpect(jsonPath("$.closing").isNotEmpty());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void unknownLocaleGets400() throws Exception {
    mvc.perform(get(PATH).param("locale", "../etc")).andExpect(status().isBadRequest());
  }
}
