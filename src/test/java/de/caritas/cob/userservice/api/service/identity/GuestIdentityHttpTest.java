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
    IdentitySuggestionControllerDelegate.class,
    GuestIdentitySuggestionService.class,
    GuestIdentityCatalog.class,
    GuestUsernameAvailability.class,
    AgencyInviteLinkController.class,
    AgencyInviteLinkService.class,
    ApiResponseEntityExceptionHandler.class,
    GuestIdentityHttpTest.ContractImplementor.class
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
   * Stands in for the application's {@code UsersApi} implementor. The generated contract maps the
   * suggestion path itself, so a context without it cannot show which handler the running service
   * picks.
   */
  @org.springframework.web.bind.annotation.RestController
  @lombok.RequiredArgsConstructor
  static class ContractImplementor
      implements de.caritas.cob.userservice.generated.api.adapters.web.controller.UsersApi {
    private final IdentitySuggestionControllerDelegate delegate;

    @Override
    public org.springframework.http.ResponseEntity<
            java.util.List<de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestion>>
        suggestGuestIdentities(
            de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestionRequest
                guestIdentitySuggestionRequest) {
      return delegate.suggestGuestIdentities(guestIdentitySuggestionRequest);
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
  void theGeneratedUsersContractServesSuggestionsRatherThanAnUnimplementedStub() throws Exception {
    when(identityProvider.isUsernameAvailable(anyString())).thenReturn(true);
    mvc.perform(
            post("/users/identity-suggestions")
                .contentType("application/json")
                .content("{\"locale\":\"de\",\"count\":4,\"exclude\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
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
  void configuredOpeningHoursAreReadableWithTheInvitationContext() throws Exception {
    var link =
        AgencyInviteLink.builder()
            .token("with-hours")
            .status("ACTIVE")
            .chatType("LIVE_CHAT")
            .tenantId(1L)
            .consultingTypeId(3)
            .topicId(11L)
            .openingHours(
                "[{\"dayOfWeek\":1,\"opens\":\"09:00\",\"closes\":\"12:00\"},"
                    + "{\"dayOfWeek\":1,\"opens\":\"13:00\",\"closes\":\"17:00\"},"
                    + "{\"dayOfWeek\":2,\"opens\":\"09:00\",\"closes\":\"10:30\"}]")
            .openingHoursTimeZone("Europe/Berlin")
            .build();
    when(links.findByToken("with-hours")).thenReturn(Optional.of(link));
    mvc.perform(get("/users/invitelinks/with-hours/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openingHoursTimeZone").value("Europe/Berlin"))
        .andExpect(jsonPath("$.openingHours.length()").value(3))
        .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value(1))
        .andExpect(jsonPath("$.openingHours[0].opens").value("09:00"))
        .andExpect(jsonPath("$.openingHours[0].closes").value("12:00"))
        .andExpect(jsonPath("$.openingHours[1].opens").value("13:00"))
        .andExpect(jsonPath("$.openingHours[2].dayOfWeek").value(2));
    verify(links, never()).save(any());
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
