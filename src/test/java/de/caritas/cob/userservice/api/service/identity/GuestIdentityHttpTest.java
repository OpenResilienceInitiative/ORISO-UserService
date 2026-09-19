package de.caritas.cob.userservice.api.service.identity;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.AgencyInviteLinkController;
import de.caritas.cob.userservice.api.adapters.web.controller.IdentitySuggestionControllerDelegate;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.config.auth.SecurityConfig;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(GuestIdentityHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class GuestIdentityHttpTest {
  @Configuration
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    GuestIdentityHttpTest.SuggestionEndpoint.class,
    IdentitySuggestionControllerDelegate.class,
    GuestIdentitySuggestionService.class,
    GuestIdentityCatalog.class,
    GuestUsernameAvailability.class,
    AgencyInviteLinkController.class,
    AgencyInviteLinkService.class,
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

  /**
   * Exercises the delegate over real HTTP. It deliberately maps the path the plain way, without the
   * generated contract's consumes/produces conditions, so that it stays harmless if another
   * integration context ever scans it: the generated mapping is the more specific one and wins.
   * That the production controller really overrides the generated operation is guarded separately
   * by GeneratedContractOverrideTest.
   */
  @org.springframework.web.bind.annotation.RestController
  @lombok.RequiredArgsConstructor
  static class SuggestionEndpoint {
    private final IdentitySuggestionControllerDelegate delegate;

    @org.springframework.web.bind.annotation.PostMapping("/users/identity-suggestions")
    public org.springframework.http.ResponseEntity<
            java.util.List<de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestion>>
        suggest(
            @org.springframework.web.bind.annotation.RequestBody(required = false)
                de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestionRequest
                    request) {
      return delegate.suggestGuestIdentities(request);
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean IdentityUsernameAvailability identityProvider;
  @MockitoBean UserRepository users;
  @MockitoBean MatrixUserClient matrix;
  @MockitoBean AgencyInviteLinkRepository links;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean TopicService topics;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean ConsultingTypeService consultingTypes;
  @MockitoBean AgencyService agencies;
  @MockitoBean CreateAnonymousEnquiryFacade provisioning;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anonymousBrowserCanReadFourSuggestionsWithoutCsrfOrProvisioning() throws Exception {
    when(identityProvider.isUsernameAvailable(anyString())).thenReturn(true);
    mvc.perform(
            post("/users/identity-suggestions")
                .contentType("application/json")
                .content("{\"locale\":\"de\",\"count\":4,\"exclude\":[]}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].username").isString())
        .andExpect(jsonPath("$[0].avatarKey").isString())
        .andExpect(jsonPath("$[0].accessToken").doesNotExist());
    verifyNoInteractions(links, provisioning, agencies);
  }

  @Test
  void malformedRequestsCannotReachIdentitySystems() throws Exception {
    mvc.perform(
            post("/users/identity-suggestions")
                .contentType("application/json")
                .content("{\"locale\":\"de\",\"count\":1000,\"exclude\":[]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(users, identityProvider, matrix, provisioning);
  }

  @Test
  void dependencyFailureIsRetryableNotAvailable() throws Exception {
    when(identityProvider.isUsernameAvailable(anyString()))
        .thenThrow(new ServiceUnavailableException("Unavailable"));
    mvc.perform(
            post("/users/identity-suggestions")
                .contentType("application/json")
                .content("{\"locale\":\"de\",\"count\":1,\"exclude\":[]}"))
        .andExpect(status().isServiceUnavailable());
    verifyNoInteractions(provisioning);
  }

  @Test
  void anonymousContextReadUsesRealSecurityAndDoesNotCreateAnAccount() throws Exception {
    var link =
        AgencyInviteLink.builder()
            .token("synthetic")
            .status("ACTIVE")
            .chatType("LIVE_CHAT")
            .tenantId(1L)
            .consultingTypeId(3)
            .topicId(11L)
            .build();
    when(links.findByToken("synthetic")).thenReturn(Optional.of(link));
    mvc.perform(get("/users/invitelinks/synthetic/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicId").value(11))
        .andExpect(jsonPath("$.agencyId").isEmpty())
        .andExpect(jsonPath("$.accessToken").doesNotExist());
    verify(links, never()).save(any());
    verifyNoInteractions(provisioning);
  }

  @Test
  void occupiedMatrixCandidatesAreNotReturnedThroughThePublicContract() throws Exception {
    when(identityProvider.isUsernameAvailable(anyString())).thenReturn(true);
    when(matrix.userExistsStrict(anyString())).thenReturn(true);
    mvc.perform(
            post("/users/identity-suggestions")
                .contentType("application/json")
                .content("{\"locale\":\"de\",\"count\":1,\"exclude\":[]}"))
        .andExpect(status().isServiceUnavailable());
    verify(matrix, atLeastOnce()).userExistsStrict(anyString());
    verifyNoInteractions(provisioning);
  }
}
