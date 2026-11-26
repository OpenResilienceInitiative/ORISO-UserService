package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** US#1796: a deleted guest's still-signed token is refused on every endpoint, with the reason. */
class TemporaryAccountDeletedAccessFilterTest {

  @RestController
  static class AnyAskerEndpoints {
    @GetMapping("/users/sessions/askers")
    String sessions() {
      return "sessions";
    }

    @GetMapping("/users/call-knocks/mine/7")
    String knock() {
      return "knock";
    }
  }

  private static JwtAuthenticationToken tokenOf(String subject) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject(subject)
            .claim("realm_access", Map.of("roles", List.of("user")))
            .build());
  }

  private static MockMvc mvcWith(TemporaryAccountDeletedAccessFilter filter) {
    return MockMvcBuilders.standaloneSetup(new AnyAskerEndpoints()).addFilters(filter).build();
  }

  @Test
  void refusesEveryRequestOfADeletedGuest_With401AndTheReason() throws Exception {
    var mvc = mvcWith(new TemporaryAccountDeletedAccessFilter(Set.of("gone-guest")::contains));

    for (var path : List.of("/users/sessions/askers", "/users/call-knocks/mine/7")) {
      var result =
          mvc.perform(get(path).principal(tokenOf("gone-guest")))
              .andExpect(status().isUnauthorized())
              .andExpect(header().string("X-Reason", "TEMPORARY_ACCOUNT_DELETED"))
              .andReturn();
      assertThat(result.getResponse().getContentAsString()).isEmpty();
    }
  }

  @Test
  void letsEveryoneElseThrough() throws Exception {
    var mvc = mvcWith(new TemporaryAccountDeletedAccessFilter(Set.of("gone-guest")::contains));

    mvc.perform(get("/users/sessions/askers").principal(tokenOf("live-guest")))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("X-Reason"));
    mvc.perform(get("/users/sessions/askers")).andExpect(status().isOk());
  }

  @Test
  void failsOpenWhenTheLookupIsUnavailable() throws Exception {
    var mvc =
        mvcWith(
            new TemporaryAccountDeletedAccessFilter(
                id -> {
                  throw new IllegalStateException("database down");
                }));

    mvc.perform(get("/users/sessions/askers").principal(tokenOf("anyone")))
        .andExpect(status().isOk());
  }
}
