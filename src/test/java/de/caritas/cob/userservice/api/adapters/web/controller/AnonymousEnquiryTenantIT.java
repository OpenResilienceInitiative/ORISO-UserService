package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
 * An anonymous enquiry writes a user and a session, so on a multi-tenant deployment it must run in
 * a tenant. The route is public: the tenant comes from the request, as for every other public route
 * that is not exempt from tenant resolution.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class AnonymousEnquiryTenantIT {

  private static final long TENANT = 7L;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;

  @Test
  void createAnonymousEnquiry_Should_RunInTheResolvedTenant() throws Exception {
    when(tenantResolverService.resolve(any())).thenReturn(TENANT);
    when(tenantService.getRestrictedTenantData(TENANT))
        .thenReturn(new RestrictedTenantDTO().subdomain("seven"));
    var tenantSeenByFacade = new AtomicReference<Long>();
    when(createAnonymousEnquiryFacade.createAnonymousEnquiry(any()))
        .thenAnswer(
            invocation -> {
              tenantSeenByFacade.set(TenantContext.getCurrentTenant());
              return new CreateAnonymousEnquiryResponseDTO().sessionId(1L);
            });

    mockMvc
        .perform(
            post("/conversations/askers/anonymous/new")
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-Token", "test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consultingType\": 1}"))
        .andExpect(status().isCreated());

    assertThat(tenantSeenByFacade.get()).isEqualTo(TENANT);
  }
}
