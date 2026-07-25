package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.GetChatSeriesOccurrences200ResponseInner;
import de.caritas.cob.userservice.api.adapters.web.dto.OccurrenceOverrideRequest;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class ChatSeriesControllerAuthorizationIT {

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", "test");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void consultantCanReadSeriesOccurrences() throws Exception {
    mvc.perform(
            get("/users/chat-series/999999/occurrences")
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                        .jwt(token -> token.claim("userId", "consultant-1")))
                .cookie(CSRF_COOKIE)
                .header("X-CSRF-Token", "test")
                .queryParam("from", "2026-07-01T00:00:00Z")
                .queryParam("to", "2026-08-01T00:00:00Z")
                .queryParam("limit", "50"))
        .andExpect(status().isNotFound());
  }

  @Test
  void consultantWriteRouteReachesSeriesAuthorizationInsteadOfGeneratedStub() throws Exception {
    mvc.perform(
            post("/users/chat-series/999999/occurrences/skip")
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                        .jwt(token -> token.claim("userId", "consultant-1")))
                .cookie(CSRF_COOKIE)
                .header("X-CSRF-Token", "test")
                .queryParam("originalStartUtc", "2026-07-20T16:00:00Z"))
        .andExpect(status().isForbidden());
  }

  @Test
  void seriesSkipHasExactlyOneConcreteHandler() {
    var handlers =
        handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(
                entry ->
                    entry
                        .getKey()
                        .getPatternValues()
                        .contains("/users/chat-series/{seriesId}/occurrences/skip"))
            .filter(
                entry ->
                    entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.POST))
            .map(java.util.Map.Entry::getValue)
            .toList();

    assertThat(handlers).hasSize(1);
    assertThat(handlers.getFirst().getBeanType()).isEqualTo(UserController.class);
    assertThat(handlers.getFirst().getMethod().getName()).isEqualTo("skipChatSeriesOccurrence");
  }

  @Test
  void occurrenceOverrideUsesOrdinaryNullableJsonFields() {
    assertThatCode(
            () ->
                objectMapper.readValue(
                    """
                    {"originalStartUtc":"2026-09-20T16:00:00Z","capacity":14}
                    """,
                    OccurrenceOverrideRequest.class))
        .doesNotThrowAnyException();
  }

  @Test
  void occurrenceCapacitySerializesAsANumber() throws Exception {
    var occurrence = new GetChatSeriesOccurrences200ResponseInner().capacity(14);

    assertThat(objectMapper.writeValueAsString(occurrence)).contains("\"capacity\":14");
  }
}
