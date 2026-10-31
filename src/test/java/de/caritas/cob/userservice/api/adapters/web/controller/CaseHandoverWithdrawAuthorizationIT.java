package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The withdraw route must pass the security filter for counsellors on both prefixes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class CaseHandoverWithdrawAuthorizationIT {

  @Autowired private MockMvc mvc;
  @MockitoBean private CaseHandoverService caseHandoverService;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/users/sessions/123/case-handover/501/withdraw",
        "/service/users/sessions/123/case-handover/501/withdraw"
      })
  void consultantReachesTheWithdrawal(String url) throws Exception {
    when(caseHandoverService.withdrawOffer(anyLong(), anyLong()))
        .thenReturn(CaseHandoverStatus.builder().sessionId(123L).status("WITHDRAWN").build());

    mvc.perform(
            post(url)
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-TOKEN", "test")
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))
                        .jwt(token -> token.claim("userId", "consultant-1"))))
        .andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/users/sessions/123/case-handover/501/withdraw",
        "/service/users/sessions/123/case-handover/501/withdraw"
      })
  void askerIsForbidden(String url) throws Exception {
    mvc.perform(
            post(url)
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-TOKEN", "test")
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT))
                        .jwt(token -> token.claim("userId", "asker-1"))))
        .andExpect(status().isForbidden());
  }
}
