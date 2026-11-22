package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ORISO-UserService#1697: {@code POST /conversations/askers/anonymous/new} had no caller left in
 * ORISO-Frontend or any other ORISO repository, yet it was public and could still mint guest
 * accounts. It is gone: a request without a login is refused and nothing is created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class RemovedAnonymousEnquiryEndpointIT {

  private static final long TENANT = 7L;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/conversations/askers/anonymous/new",
        "/service/conversations/askers/anonymous/new"
      })
  void theRemovedPublicGuestEndpoint_Should_CreateNoGuest(String path) throws Exception {
    when(tenantResolverService.resolve(any())).thenReturn(TENANT);
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(new RestrictedTenantDTO().id(TENANT).active(true).subdomain("seven"));

    var status =
        mockMvc
            .perform(
                post(path)
                    .cookie(new Cookie("CSRF-TOKEN", "test"))
                    .header("X-CSRF-Token", "test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"consultingType\":1}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isBetween(400, 499);
    verifyNoInteractions(createAnonymousEnquiryFacade);
  }
}
