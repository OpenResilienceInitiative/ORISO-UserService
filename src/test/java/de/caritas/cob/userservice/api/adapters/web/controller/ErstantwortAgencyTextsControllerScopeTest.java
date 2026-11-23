package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPlatformTextService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * The real {@link AdminScope}: centre 5 is this admin's centre, centre 9 is not. A mock that
 * accepts every {@code assertMay} would still pass if the controller checked the wrong id.
 */
class ErstantwortAgencyTextsControllerScopeTest {

  private static final String BODY = "{\"texts\":{\"greeting\":{\"de\":\"<p>Hallo</p>\"}}}";

  private MockMvc mvc;
  private ErstantwortPlatformTextService texts;

  @BeforeEach
  void setUp() {
    var actor = new AuthenticatedUser();
    actor.setUserId("centre-admin");
    actor.setRoles(Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue()));
    var ownCentre = mock(AdminAgency.class);
    when(ownCentre.getAgencyId()).thenReturn(5L);
    var adminAgencies = mock(AdminAgencyRepository.class);
    when(adminAgencies.findByAdminId("centre-admin")).thenReturn(List.of(ownCentre));
    var scope =
        new AdminScope(
            actor,
            mock(AdminRepository.class),
            adminAgencies,
            mock(ConsultantRepository.class),
            mock(ConsultantAgencyRepository.class),
            mock(AgencyService.class),
            mock(UserRepository.class),
            mock(SessionRepository.class),
            mock(UserAgencyRepository.class));
    texts = mock(ErstantwortPlatformTextService.class);
    when(texts.readAgencyTexts(5L)).thenReturn(Map.of());
    var agencies = mock(AgencyService.class);
    when(agencies.getAgencyWithoutCaching(anyLong())).thenReturn(new AgencyDTO());
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ErstantwortAgencyTextsController(texts, scope, agencies))
            .setControllerAdvice(new StatusAdvice())
            .setCustomArgumentResolvers(new JwtResolver())
            .build();
  }

  @Test
  void theAdminsOwnCentreIsReadable() throws Exception {
    mvc.perform(get("/useradmin/erstantwort/agencies/5")).andExpect(status().isOk());
  }

  @Test
  void anotherCentreIsForbiddenAndNothingIsStored() throws Exception {
    mvc.perform(
            put("/useradmin/erstantwort/agencies/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
    verify(texts, never()).replaceAgencyTexts(anyLong(), anyMap(), any());
  }

  /**
   * Standalone MockMvc has no security filter, so the controller's Jwt argument is supplied here.
   */
  static class JwtResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return Jwt.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
      return Jwt.withTokenValue("token").header("alg", "none").subject("centre-admin").build();
    }
  }

  @RestControllerAdvice
  static class StatusAdvice {
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Void> forbidden(ForbiddenException denied) {
      denied.executeLogging();
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> status(ResponseStatusException error) {
      return ResponseEntity.status(error.getStatusCode()).build();
    }
  }
}
