package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AgencyAdminContactsAuthorizationIT {

  private static final String URL = "/users/agencies/999999/admin-contacts";

  @Autowired private MockMvc mvc;

  @Test
  void anonymousCallerIsRejected() throws Exception {
    mvc.perform(get(URL)).andExpect(status().isUnauthorized());
  }

  @Test
  void askerIsForbidden() throws Exception {
    mvc.perform(
            get(URL)
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT))
                        .jwt(token -> token.claim("userId", "asker-1"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void consultantOfAnotherCentreIsForbidden() throws Exception {
    mvc.perform(
            get(URL)
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                        .jwt(token -> token.claim("userId", "consultant-1"))))
        .andExpect(status().isForbidden());
  }
}
