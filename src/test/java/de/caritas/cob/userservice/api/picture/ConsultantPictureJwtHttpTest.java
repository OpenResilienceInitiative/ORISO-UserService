package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.httpheader.*;
import de.caritas.cob.userservice.api.tenant.*;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Actual bearer filter, role conversion, request-scoped caller construction and tenant filter. */
@SpringJUnitWebConfig({
  ConsultantPictureHttpTest.Config.class,
  ConsultantPictureJwtHttpTest.Config.class
})
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=false"
    })
class ConsultantPictureJwtHttpTest {
  @Configuration
  @Import({
    HttpTenantFilter.class,
    TenantResolverService.class,
    TechnicalOrSuperAdminUserTenantResolver.class,
    AccessTokenTenantResolver.class,
    CustomHeaderTenantResolver.class,
    TenantHeaderSupplier.class,
    HttpHeadersResolver.class
  })
  static class Config {
    @Bean
    org.springframework.web.multipart.support.StandardServletMultipartResolver multipartResolver() {
      return new org.springframework.web.multipart.support.StandardServletMultipartResolver();
    }

    @Bean
    @Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
    AuthenticatedUser authenticatedUser(HttpServletRequest request) {
      var config = new KeycloakConfig();
      config.setPrincipalAttribute("preferred_username");
      return config.authenticatedUser(request, new UsernameTranscoder());
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean ConsultantPictureStore store;
  @MockitoBean ClamAvPictureScanner scanner;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean AdminUserFacade admins;
  @MockitoBean ConsultantAgencyAdminService agencies;
  @MockitoBean TenantService tenants;
  @MockitoBean SubdomainTenantResolver subdomains;
  @MockitoBean MultitenancyWithSingleDomainTenantResolver singleDomain;
  MockMvc mvc;
  final String id = "14c9b484-605f-44a3-8c36-cc11447e3a10";
  byte[] png;

  @BeforeEach
  void setup() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    png = PictureIntakeTest.png(2, 2);
    var target = new Consultant();
    target.setId(id);
    target.setTenantId(1L);
    when(consultants.findByIdAndDeleteDateIsNull(id)).thenReturn(Optional.of(target));
    when(store.read(id)).thenReturn(new ConsultantPicture(id, png, "image/png"));
    when(tenants.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("synthetic"));
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  void jwt(Long tenant, String... roles) {
    var token =
        Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .subject("caller")
            .claim("preferred_username", "synthetic")
            .claim("realm_access", Map.of("roles", List.of(roles)));
    if (tenant != null) token.claim("tenantId", tenant);
    when(decoder.decode("synthetic")).thenReturn(token.build());
  }

  String path(String prefix) {
    return prefix + "/useradmin/consultants/" + id + "/picture";
  }

  MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder request, long tenant) {
    return request
        .header("Authorization", "Bearer synthetic")
        .header("tenantId", tenant)
        .cookie(new Cookie("CSRF-TOKEN", "synthetic"))
        .header("X-CSRF-Token", "synthetic");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void actualJwtColleagueReadsButSeekerAndWrongTenantCannot(String prefix) throws Exception {
    jwt(1L, "consultant");
    mvc.perform(request(get(path(prefix)), 1))
        .andExpect(status().isOk())
        .andExpect(content().bytes(png));
    clearInvocations(store, consultants);
    jwt(1L, "user");
    mvc.perform(request(get(path(prefix)), 1)).andExpect(status().isForbidden());
    verifyNoInteractions(store, consultants);
    jwt(2L, "consultant");
    mvc.perform(request(get(path(prefix)), 2)).andExpect(status().isForbidden());
    verifyNoInteractions(store);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void actualRestrictedRoleCombinationRequiresSharedAgency(String prefix) throws Exception {
    jwt(1L, "restricted-agency-admin", "restricted-consultant-admin");
    when(admins.findAdminUserAgencyIds("caller")).thenReturn(List.of(1L));
    when(agencies.findConsultantAgencyIds(id)).thenReturn(List.of(2L));
    mvc.perform(request(put(path(prefix)).contentType("image/png").content(png), 1))
        .andExpect(status().isForbidden());
    verifyNoInteractions(scanner, store);
    when(agencies.findConsultantAgencyIds(id)).thenReturn(List.of(1L));
    mvc.perform(request(put(path(prefix)).contentType("image/png").content(png), 1))
        .andExpect(status().isNoContent());
    verify(scanner).scan(png);
    verify(store).replace(id, png, "image/png");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("restrictedRoleForms")
  void everyAcceptedRestrictedRoleFormChecksAgencyForEveryPictureMethod(String prefix, String form)
      throws Exception {
    var roles =
        form.equals("normalized")
            ? List.of("ROLE_restricted_agency_admin", "ROLE_restricted_consultant_admin")
            : List.of("restricted-agency-admin", "restricted-consultant-admin");
    var token =
        Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .subject("caller")
            .claim("preferred_username", "synthetic")
            .claim("tenantId", 1L);
    if (form.equals("resource")) {
      token.claim("resource_access", Map.of("admin-client", Map.of("roles", roles)));
    } else {
      token.claim("realm_access", Map.of("roles", roles));
    }
    when(decoder.decode("synthetic")).thenReturn(token.build());
    when(admins.findAdminUserAgencyIds("caller")).thenReturn(List.of(1L));
    when(agencies.findConsultantAgencyIds(id)).thenReturn(List.of(2L));
    mvc.perform(request(get(path(prefix)), 1)).andExpect(status().isForbidden());
    mvc.perform(request(put(path(prefix)).contentType("image/png").content(png), 1))
        .andExpect(status().isForbidden());
    mvc.perform(request(delete(path(prefix)), 1)).andExpect(status().isForbidden());
    verifyNoInteractions(scanner, store);
    when(agencies.findConsultantAgencyIds(id)).thenReturn(List.of(1L));
    mvc.perform(request(get(path(prefix)), 1)).andExpect(status().isOk());
    mvc.perform(request(put(path(prefix)).contentType("image/png").content(png), 1))
        .andExpect(status().isNoContent());
    mvc.perform(request(delete(path(prefix)), 1)).andExpect(status().isNoContent());
    verify(scanner).scan(png);
    verify(store).read(id);
    verify(store).replace(id, png, "image/png");
    verify(store).remove(id);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      restrictedRoleForms() {
    return java.util.stream.Stream.of("", "/service")
        .flatMap(
            prefix ->
                java.util.stream.Stream.of("realm", "resource", "normalized")
                    .map(form -> org.junit.jupiter.params.provider.Arguments.of(prefix, form)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void zeroTenantAloneIsInsufficientButActualPlatformAdminMayCrossTenant(String prefix)
      throws Exception {
    jwt(0L, "user-admin");
    mvc.perform(request(get(path(prefix)), 0)).andExpect(status().isForbidden());
    jwt(0L, "user-admin", "agency-admin", "tenant-admin", "restricted-agency-admin");
    mvc.perform(request(get(path(prefix)), 0)).andExpect(status().isOk());
    verify(store).read(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void spoofedHeaderIsStoppedByExistingTenantFilterBeforePictureAccess(String prefix) {
    jwt(2L, "user-admin");
    assertThatThrownBy(
            () -> mvc.perform(request(put(path(prefix)).contentType("image/png").content(png), 1)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(consultants, scanner, store);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void encodedPictureRoutesRefuseMultipartBeforeRealServletResolver(String prefix)
      throws Exception {
    jwt(1L, "user-admin");
    assertThat(context.getBean("multipartResolver"))
        .isInstanceOf(
            org.springframework.web.multipart.support.StandardServletMultipartResolver.class);
    for (String uri :
        List.of(
            path(prefix),
            path(prefix).replace("/picture", "/%70icture"),
            path(prefix).replace("/useradmin", "/%75seradmin"),
            path(prefix).replace("/consultants", "/%63onsultants"))) {
      var parts = new java.util.concurrent.atomic.AtomicInteger();
      var upload =
          put(java.net.URI.create(uri))
              .contentType("multipart/form-data; boundary=synthetic")
              .content("synthetic")
              .with(
                  request -> {
                    var observed = spy(request);
                    try {
                      doAnswer(
                              invocation -> {
                                parts.incrementAndGet();
                                return List.of();
                              })
                          .when(observed)
                          .getParts();
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                    return observed;
                  });
      mvc.perform(request(upload, 1)).andExpect(status().isUnsupportedMediaType());
      assertThat(parts.get()).as("servlet getParts calls for %s", uri).isZero();
    }
    verifyNoInteractions(scanner, store);
  }
}
