package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AdminListPreferenceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * An admin's last chosen sort per list tab is kept on the server, per person (#1263 slice B4), so
 * it follows them to every device. Full Spring context, real security chain, H2 persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AdminListPreferencesE2EIT {

  private static final String PATH = "/useradmin/list-preferences";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final String ADMIN_A = "5b1f6c0e-0000-4000-8000-00000000000a";
  private static final String ADMIN_B = "5b1f6c0e-0000-4000-8000-00000000000b";

  @Autowired private MockMvc mockMvc;
  @Autowired private AdminListPreferenceRepository repository;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void savedSort_isReadBack_andOverwrittenByTheNextChoice() throws Exception {
    actAs(ADMIN_A);

    putSort("consultants", "LASTNAME", "DESC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isNoContent());

    getOwn(AuthorityValue.USER_ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sorts", aMapWithSize(1)))
        .andExpect(jsonPath("$.sorts.consultants.field", is("LASTNAME")))
        .andExpect(jsonPath("$.sorts.consultants.order", is("DESC")));

    putSort("consultants", "EMAIL", "ASC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isNoContent());

    getOwn(AuthorityValue.USER_ADMIN)
        .andExpect(jsonPath("$.sorts", aMapWithSize(1)))
        .andExpect(jsonPath("$.sorts.consultants.field", is("EMAIL")))
        .andExpect(jsonPath("$.sorts.consultants.order", is("ASC")));
  }

  @Test
  void anotherPersonsSorts_areNeitherVisibleNorTouched() throws Exception {
    actAs(ADMIN_A);
    putSort("consultants", "LASTNAME", "DESC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isNoContent());

    actAs(ADMIN_B);
    getOwn(AuthorityValue.TENANT_ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sorts", aMapWithSize(0)));
    putSort("tenant-admins", "TENANT_ID", "ASC", AuthorityValue.TENANT_ADMIN)
        .andExpect(status().isNoContent());
    putSort("consultants", "UPDATE_DATE", "ASC", AuthorityValue.TENANT_ADMIN)
        .andExpect(status().isNoContent());

    actAs(ADMIN_A);
    getOwn(AuthorityValue.USER_ADMIN)
        .andExpect(jsonPath("$.sorts", aMapWithSize(1)))
        .andExpect(jsonPath("$.sorts.consultants.field", is("LASTNAME")))
        .andExpect(jsonPath("$.sorts.consultants.order", is("DESC")));
  }

  @Test
  void everyAdminRoleThatSeesUserLists_mayKeepItsOwnSorts() throws Exception {
    actAs(ADMIN_A);
    putSort("agency-admins", "FIRSTNAME", "ASC", AuthorityValue.SINGLE_TENANT_ADMIN)
        .andExpect(status().isNoContent());
    putSort("consultants", "FIRSTNAME", "DESC", AuthorityValue.RESTRICTED_AGENCY_ADMIN)
        .andExpect(status().isNoContent());
    putSort("platform-admins", "TENANT_ID", "DESC", AuthorityValue.TENANT_ADMIN)
        .andExpect(status().isNoContent());

    getOwn(AuthorityValue.RESTRICTED_AGENCY_ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sorts", aMapWithSize(3)))
        .andExpect(jsonPath("$.sorts.platform-admins.field", is("TENANT_ID")));
  }

  @Test
  void aFieldTheTabCannotSortBy_isRejected() throws Exception {
    actAs(ADMIN_A);

    putSort("consultants", "TENANT_ID", "ASC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());
    putSort("agency-admins", "TENANT_ID", "ASC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());
    putSort("consultants", "USERNAME", "ASC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());
    putSort("consultants", "LASTNAME", "UP", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());
    putSort("sessions", "LASTNAME", "ASC", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());
    putBody("consultants", "{\"order\":\"ASC\"}", AuthorityValue.USER_ADMIN)
        .andExpect(status().isBadRequest());

    getOwn(AuthorityValue.USER_ADMIN).andExpect(jsonPath("$.sorts", aMapWithSize(0)));
  }

  @Test
  void withoutAToken_theEndpointsAreClosed() throws Exception {
    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put(PATH + "/sorts/consultants")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"LASTNAME\",\"order\":\"ASC\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aCounsellorWithoutAnAdminRole_isForbidden() throws Exception {
    actAs(ADMIN_A);
    getOwn(AuthorityValue.CONSULTANT_DEFAULT).andExpect(status().isForbidden());
    putSort("consultants", "LASTNAME", "ASC", AuthorityValue.CONSULTANT_DEFAULT)
        .andExpect(status().isForbidden());
  }

  private void actAs(String keycloakUserId) {
    when(authenticatedUser.getUserId()).thenReturn(keycloakUserId);
  }

  private ResultActions getOwn(String authority) throws Exception {
    return mockMvc.perform(get(PATH).with(jwt().authorities(() -> authority)));
  }

  private ResultActions putSort(String tab, String field, String order, String authority)
      throws Exception {
    return putBody(tab, "{\"field\":\"" + field + "\",\"order\":\"" + order + "\"}", authority);
  }

  private ResultActions putBody(String tab, String body, String authority) throws Exception {
    return mockMvc.perform(
        put(PATH + "/sorts/" + tab)
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .with(jwt().authorities(() -> authority))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
