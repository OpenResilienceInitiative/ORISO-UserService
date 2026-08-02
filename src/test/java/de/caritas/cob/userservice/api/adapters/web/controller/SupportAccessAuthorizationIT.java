package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService;
import de.caritas.cob.userservice.api.service.support.SupportAccessAuditService;
import de.caritas.cob.userservice.api.service.support.SupportAccessSessionService;
import de.caritas.cob.userservice.api.service.support.SupportTargetService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Permission matrix for every support-access endpoint (ADR-018 §5).
 *
 * <p>This asserts the authorization layer only: an allowed caller must get past it (anything other
 * than 401/403), a denied caller must be stopped there. Whether a permitted call then succeeds
 * business-wise is covered by the service tests — mixing the two would make a broken rule look like
 * a broken mock.
 */
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      // The shared demo seed script is irrelevant here and its legacy INSERTs do not load on every
      // H2 version. Authorization is decided before any repository is touched, so skipping the seed
      // keeps this matrix independent of that fixture.
      "spring.sql.init.mode=never"
    })
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.ANY)
class SupportAccessAuthorizationIT {

  // The real names from application.properties. The /useradmin paths are CSRF-whitelisted, but the
  // /users/support-access ones are not, so a state-changing call needs a genuine matching pair —
  // otherwise the CSRF filter answers 403 and the test would be measuring CSRF, not authorization.
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final String SUPPORT_ADMINS = "/useradmin/supportadmins";
  private static final String SUPPORT_ADMINS_SEARCH = "/useradmin/supportadmins/search";
  private static final String SUPPORT_ADMIN_DISABLE = "/useradmin/supportadmins/gsa-1/disable";
  private static final String SUPPORT_ADMIN_ENABLE = "/useradmin/supportadmins/gsa-1/enable";
  private static final String SUPPORT_TARGETS = "/useradmin/support-targets/search";
  private static final String SUPPORT_AUDIT = "/useradmin/support-access/audit";
  private static final String REQUESTS = "/users/support-access/requests";
  private static final String REQUESTS_PENDING = "/users/support-access/requests/pending";
  private static final String REQUEST_CONFIRM = "/users/support-access/requests/hs-1/confirm";
  private static final String REQUEST_DECLINE = "/users/support-access/requests/hs-1/decline";
  private static final String SESSIONS_ACTIVE = "/users/support-access/sessions/active";
  private static final String SESSION_TERMINATE = "/users/support-access/sessions/s-1/terminate";
  private static final String SESSION_CALL_ROOM = "/users/support-access/sessions/s-1/call-room";
  private static final String TWO_FACTOR_APP = "/users/2fa/app";

  @Autowired private MockMvc mvc;

  @MockitoBean private AdminUserFacade adminUserFacade;
  @MockitoBean private HandshakeService handshakeService;
  @MockitoBean private SupportTargetService supportTargetService;
  @MockitoBean private SupportAccessAuditService supportAccessAuditService;
  @MockitoBean private SupportAccessSessionService supportAccessSessionService;

  /**
   * Every support endpoint must refuse an unauthenticated caller. Whether that comes out as 401 or
   * 403 depends on which filter answers first, and pinning that down would test the filter order
   * rather than the rule.
   */
  @ParameterizedTest(name = "{0} {1} without a token is refused")
  @MethodSource("allEndpoints")
  void everyEndpoint_Should_RejectAnonymousCallers(String method, String path) throws Exception {
    var status = mvc.perform(request(method, path)).andReturn().getResponse().getStatus();

    org.assertj.core.api.Assertions.assertThat(status)
        .as("%s %s must refuse an unauthenticated caller", method, path)
        .isIn(401, 403);
  }

  @ParameterizedTest(name = "{2} on {0} {1} -> allowed={3}")
  @MethodSource("permissionMatrix")
  void endpoint_Should_EnforceItsRole(String method, String path, String authority, boolean allowed)
      throws Exception {
    var result =
        mvc.perform(request(method, path).with(user("someone").authorities(authority(authority))))
            .andReturn();

    var status = result.getResponse().getStatus();
    if (allowed) {
      org.assertj.core.api.Assertions.assertThat(status)
          .as("%s %s must let %s past the authorization layer", method, path, authority)
          .isNotIn(401, 403);
    } else {
      org.assertj.core.api.Assertions.assertThat(status)
          .as("%s %s must refuse %s", method, path, authority)
          .isEqualTo(403);
    }
  }

  /**
   * The one rule that is easy to get wrong: a consultant is a first-class participant of the
   * handshake, but must never reach the admin-side surfaces.
   */
  @Test
  void consultant_Should_NotReachAnyAdminSurface() throws Exception {
    for (var path : List.of(SUPPORT_ADMINS_SEARCH, SUPPORT_TARGETS, SUPPORT_AUDIT)) {
      mvc.perform(
              get(path).with(user("c").authorities(authority(AuthorityValue.CONSULTANT_DEFAULT))))
          .andExpect(status().isForbidden());
    }
  }

  /**
   * A Global Support Admin is useless until a second factor is enrolled, so it MUST be able to
   * reach the enrolment endpoint. Leaving it out deadlocks the account exactly the way the platform
   * admin was once deadlocked: encouraged to set 2FA up, but forbidden from doing it.
   */
  @Test
  void globalSupportAdmin_Should_BeAbleToEnrolItsSecondFactor() throws Exception {
    var status =
        mvc.perform(
                request("PUT", TWO_FACTOR_APP)
                    .with(user("gsa").authorities(authority(AuthorityValue.GLOBAL_SUPPORT_ADMIN))))
            .andReturn()
            .getResponse()
            .getStatus();

    org.assertj.core.api.Assertions.assertThat(status)
        .as("a support admin must be allowed to set up its own second factor")
        .isNotIn(401, 403);
  }

  /** And the mirror image: a support admin must not reach consultant or asker data. */
  @Test
  void globalSupportAdmin_Should_NotReachConsultantSurfaces() throws Exception {
    for (var path : List.of("/users/sessions/consultants", "/users/chats")) {
      mvc.perform(
              get(path)
                  .with(user("gsa").authorities(authority(AuthorityValue.GLOBAL_SUPPORT_ADMIN))))
          .andExpect(status().isForbidden());
    }
  }

  private static Stream<Arguments> allEndpoints() {
    return Stream.of(
        Arguments.of("POST", SUPPORT_ADMINS),
        Arguments.of("GET", SUPPORT_ADMINS_SEARCH),
        Arguments.of("POST", SUPPORT_ADMIN_DISABLE),
        Arguments.of("POST", SUPPORT_ADMIN_ENABLE),
        Arguments.of("GET", SUPPORT_TARGETS),
        Arguments.of("GET", SUPPORT_AUDIT),
        Arguments.of("POST", REQUESTS),
        Arguments.of("GET", REQUESTS_PENDING),
        Arguments.of("POST", REQUEST_CONFIRM),
        Arguments.of("POST", REQUEST_DECLINE),
        Arguments.of("GET", SESSIONS_ACTIVE),
        Arguments.of("POST", SESSION_TERMINATE),
        Arguments.of("PUT", SESSION_CALL_ROOM));
  }

  private static Stream<Arguments> permissionMatrix() {
    return Stream.of(
        // Managing support admins is Platform-Admin-only territory.
        row("GET", SUPPORT_ADMINS_SEARCH, AuthorityValue.TENANT_ADMIN, true),
        row("GET", SUPPORT_ADMINS_SEARCH, AuthorityValue.GLOBAL_SUPPORT_ADMIN, false),
        row("GET", SUPPORT_ADMINS_SEARCH, AuthorityValue.RESTRICTED_AGENCY_ADMIN, false),
        row("GET", SUPPORT_ADMINS_SEARCH, AuthorityValue.CONSULTANT_DEFAULT, false),
        row("GET", SUPPORT_ADMINS_SEARCH, AuthorityValue.USER_DEFAULT, false),
        row("POST", SUPPORT_ADMIN_DISABLE, AuthorityValue.TENANT_ADMIN, true),
        row("POST", SUPPORT_ADMIN_DISABLE, AuthorityValue.GLOBAL_SUPPORT_ADMIN, false),
        row("POST", SUPPORT_ADMIN_ENABLE, AuthorityValue.TENANT_ADMIN, true),
        row("POST", SUPPORT_ADMIN_ENABLE, AuthorityValue.RESTRICTED_AGENCY_ADMIN, false),

        // Picking a support target is the support admin's own surface and nobody else's.
        row("GET", SUPPORT_TARGETS, AuthorityValue.GLOBAL_SUPPORT_ADMIN, true),
        row("GET", SUPPORT_TARGETS, AuthorityValue.TENANT_ADMIN, false),
        row("GET", SUPPORT_TARGETS, AuthorityValue.RESTRICTED_AGENCY_ADMIN, false),
        row("GET", SUPPORT_TARGETS, AuthorityValue.CONSULTANT_DEFAULT, false),

        // Audit is readable by the admin roles; the scope itself is narrowed in the service.
        row("GET", SUPPORT_AUDIT, AuthorityValue.TENANT_ADMIN, true),
        row("GET", SUPPORT_AUDIT, AuthorityValue.RESTRICTED_AGENCY_ADMIN, true),
        row("GET", SUPPORT_AUDIT, AuthorityValue.GLOBAL_SUPPORT_ADMIN, false),
        row("GET", SUPPORT_AUDIT, AuthorityValue.CONSULTANT_DEFAULT, false),
        row("GET", SUPPORT_AUDIT, AuthorityValue.USER_DEFAULT, false),

        // The handshake itself: exactly the two participant roles, nobody else.
        row("POST", REQUESTS, AuthorityValue.GLOBAL_SUPPORT_ADMIN, true),
        row("POST", REQUESTS, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("POST", REQUESTS, AuthorityValue.TENANT_ADMIN, false),
        row("POST", REQUESTS, AuthorityValue.USER_DEFAULT, false),
        row("GET", REQUESTS_PENDING, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("GET", REQUESTS_PENDING, AuthorityValue.USER_DEFAULT, false),
        row("POST", REQUEST_CONFIRM, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("POST", REQUEST_CONFIRM, AuthorityValue.USER_DEFAULT, false),
        row("POST", REQUEST_DECLINE, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("POST", REQUEST_DECLINE, AuthorityValue.TENANT_ADMIN, false),

        // Session surfaces, same two roles.
        row("GET", SESSIONS_ACTIVE, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("GET", SESSIONS_ACTIVE, AuthorityValue.GLOBAL_SUPPORT_ADMIN, true),
        row("GET", SESSIONS_ACTIVE, AuthorityValue.USER_DEFAULT, false),
        row("POST", SESSION_TERMINATE, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("POST", SESSION_TERMINATE, AuthorityValue.USER_DEFAULT, false),
        row("PUT", SESSION_CALL_ROOM, AuthorityValue.CONSULTANT_DEFAULT, true),
        row("PUT", SESSION_CALL_ROOM, AuthorityValue.GLOBAL_SUPPORT_ADMIN, true),
        row("PUT", SESSION_CALL_ROOM, AuthorityValue.USER_DEFAULT, false));
  }

  private static Arguments row(String method, String path, String authority, boolean allowed) {
    return Arguments.of(method, path, authority, allowed);
  }

  private static org.springframework.security.core.GrantedAuthority authority(String value) {
    return new org.springframework.security.core.authority.SimpleGrantedAuthority(value);
  }

  private MockHttpServletRequestBuilder request(String method, String path) {
    var builder =
        switch (method) {
          case "POST" -> post(path);
          case "PUT" -> put(path);
          default -> get(path);
        };
    return builder
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}");
  }
}
