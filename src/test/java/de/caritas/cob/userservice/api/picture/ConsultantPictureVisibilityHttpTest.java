package de.caritas.cob.userservice.api.picture;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Issue #1049: the real filter chain for the publish switch and the advice-seeker read. Both
 * prefixes are exercised; the store is mocked so only routing and authorization are under test.
 */
@SpringJUnitWebConfig(ConsultantPictureVisibilityHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class ConsultantPictureVisibilityHttpTest {
  @Configuration
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    ConsultantPictureController.class,
    PublishedConsultantPictureController.class,
    ConsultantPictureAccess.class,
    ConsultantPictureService.class,
    PictureIntake.class,
    ApiResponseEntityExceptionHandler.class
  })
  static class Config {
    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var p = new CsrfSecurityProperties();
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("CSRF-TOKEN");
      p.setCookie(cookie);
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-CSRF-Token");
      p.setHeader(header);
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var wh = new CsrfSecurityProperties.ConfigProperty();
      wh.setProperty("X-CSRF-Whitelist");
      whitelist.setHeader(wh);
      p.setWhitelist(whitelist);
      return p;
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService inactivity;

  @MockitoBean ConsultantPictureStore store;
  @MockitoBean ClamAvPictureScanner scanner;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean AdminUserFacade admins;
  @MockitoBean ConsultantAgencyAdminService agencies;
  MockMvc mvc;
  final String id = "14c9b484-605f-44a3-8c36-cc11447e3a10";
  byte[] png;

  @BeforeEach
  void setup() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Account-inactivity gate (UserService #1175): these callers are active.
    when(inactivity.admit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    png = PictureIntakeTest.png(2, 2);
    var target = new Consultant();
    target.setId(id);
    target.setTenantId(1L);
    when(consultants.findByIdAndDeleteDateIsNull(id)).thenReturn(Optional.of(target));
    when(caller.getTenantId()).thenReturn(1L);
    when(caller.getUserId()).thenReturn("caller");
    when(caller.getGrantedAuthorities()).thenReturn(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
    when(store.readInternalOnly(id)).thenReturn(true);
    when(store.readPublished(id)).thenReturn(new ConsultantPicture(id, png, "image/png"));
  }

  MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request
        .cookie(new Cookie("CSRF-TOKEN", "synthetic"))
        .header("X-CSRF-Token", "synthetic");
  }

  MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return csrf(request)
        .with(
            user("caller")
                .authorities(
                    new SimpleGrantedAuthority(USER_ADMIN),
                    new SimpleGrantedAuthority(CONSULTANT_UPDATE)));
  }

  String visibility(String prefix) {
    return prefix + "/useradmin/consultants/" + id + "/picture/visibility";
  }

  String published(String prefix) {
    return prefix + "/users/consultants/" + id + "/picture";
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void administratorsReadAndChangeThePublishSwitch(String prefix) throws Exception {
    mvc.perform(admin(get(visibility(prefix))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.internalOnly").value(true))
        .andExpect(header().string("Cache-Control", "no-store, private"));
    mvc.perform(
            admin(
                put(visibility(prefix))
                    .contentType("application/json")
                    .content("{\"internalOnly\":false}")))
        .andExpect(status().isNoContent());
    verify(store).writeInternalOnly(id, false);
    mvc.perform(
            admin(
                put(visibility(prefix))
                    .contentType("application/json")
                    .content("{\"internalOnly\":true}")))
        .andExpect(status().isNoContent());
    verify(store).writeInternalOnly(id, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void aColleagueMayReadTheSwitchButNotChangeIt(String prefix) throws Exception {
    when(caller.getGrantedAuthorities()).thenReturn(Set.of(CONSULTANT_DEFAULT));
    var colleague = user("colleague").authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT));
    mvc.perform(get(visibility(prefix)).with(colleague)).andExpect(status().isOk());
    mvc.perform(
            csrf(put(visibility(prefix))
                    .contentType("application/json")
                    .content("{\"internalOnly\":false}"))
                .with(colleague))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void adviceSeekersAndAnonymousCallersCannotTouchTheSwitch(String prefix) throws Exception {
    mvc.perform(csrf(get(visibility(prefix)))).andExpect(status().isUnauthorized());
    mvc.perform(
            csrf(get(visibility(prefix)))
                .with(user("asker").authorities(new SimpleGrantedAuthority(USER_DEFAULT))))
        .andExpect(status().isForbidden());
    mvc.perform(
            csrf(put(visibility(prefix))
                    .contentType("application/json")
                    .content("{\"internalOnly\":false}"))
                .with(user("asker").authorities(new SimpleGrantedAuthority(USER_DEFAULT))))
        .andExpect(status().isForbidden());
    verify(store, never()).writeInternalOnly(anyString(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void anAdviceSeekerReadsAPublishedPictureAndNeverAnInternalOne(String prefix) throws Exception {
    var asker = user("asker").authorities(new SimpleGrantedAuthority(USER_DEFAULT));
    mvc.perform(get(published(prefix)).with(asker))
        .andExpect(status().isOk())
        .andExpect(content().bytes(png))
        .andExpect(content().contentType("image/png"))
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));

    when(store.readPublished(id)).thenThrow(new NotFoundException("Picture not found"));
    mvc.perform(get(published(prefix)).with(asker)).andExpect(status().isNotFound());
    mvc.perform(
            get(published(prefix))
                .with(user("guest").authorities(new SimpleGrantedAuthority(ANONYMOUS_DEFAULT))))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void thePublishedRouteStillRequiresAuthenticationAndRejectsWrites(String prefix)
      throws Exception {
    mvc.perform(get(published(prefix))).andExpect(status().isUnauthorized());
    mvc.perform(csrf(put(published(prefix)).contentType("image/png").content(png)))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            csrf(delete(published(prefix)))
                .with(user("asker").authorities(new SimpleGrantedAuthority(USER_DEFAULT))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(store);
  }
}
