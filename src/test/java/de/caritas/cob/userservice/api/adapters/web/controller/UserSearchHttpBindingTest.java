package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserSearchHttpBindingTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "%",
        "%25",
        "50%_!",
        "owner+tag@example.test",
        "Max owner+tag@example.test",
        "plain@example.test"
      })
  void boundAdminSearchPreservesLiteralText(String text) throws Exception {
    var facade = mock(AdminUserFacade.class);
    var controller = mock(UserAdminController.class, CALLS_REAL_METHODS);
    var mapper = mock(AdminDtoMapper.class);
    when(mapper.mappedFieldOf(anyString())).thenReturn("email");
    ReflectionTestUtils.setField(controller, "adminUserFacade", facade);
    ReflectionTestUtils.setField(controller, "adminDtoMapper", mapper);
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    for (String kind : new String[] {"agencyadmins", "tenantadmins"}) {
      mvc.perform(
              get(
                  URI.create(
                      "/useradmin/"
                          + kind
                          + "/search?query="
                          + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"))))
          .andExpect(status().isOk());
    }
    verify(facade).findAgencyAdminsByInfix(eq(text), any(), eq(0), eq(10), eq("email"), eq(true));
    verify(facade).findTenantAdminsByInfix(eq(text), any(), eq(0), eq(10), eq("email"), eq(true));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "%",
        "%25",
        "50%_!",
        "owner+tag@example.test",
        "Max owner+tag@example.test",
        "plain@example.test"
      })
  void boundConsultantSearchPreservesLiteralText(String text) throws Exception {
    var accounts = mock(AccountManaging.class);
    var mapper = mock(ConsultantDtoMapper.class);
    when(mapper.mappedFieldOf(anyString())).thenReturn("email");
    var delegate =
        new UserConsultantControllerDelegate(
            mock(AuthenticatedUser.class),
            mock(ConsultantAgencyService.class),
            accounts,
            mapper,
            mock(ConsultantService.class),
            mock(AdminUserFacade.class));
    var controller = mock(UserController.class, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(controller, "userConsultantControllerDelegate", delegate);
    MockMvcBuilders.standaloneSetup(controller)
        .build()
        .perform(
            get(
                URI.create(
                    "/users/consultants/search?query="
                        + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"))))
        .andExpect(status().isOk());
    verify(accounts)
        .findConsultantsByInfix(
            eq(text), eq(false), any(), any(), eq(0), eq(10), eq("email"), eq(true));
  }
}
