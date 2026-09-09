package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceCommandService;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceQueryService;
import de.caritas.cob.userservice.api.service.chat.GroupChatRoleService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hateoas.autoconfigure.HypermediaAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@WebMvcTest(
    value = UserController.class,
    excludeAutoConfiguration = HypermediaAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class UserEmailWireContractTest {
  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerAdapter adapter;
  @MockitoBean UserAccountService userAccountProvider;
  @MockitoBean UserRegistrationControllerDelegate userRegistrationControllerDelegate;
  @MockitoBean UserSessionControllerDelegate userSessionControllerDelegate;
  @MockitoBean UserAccountControllerDelegate userAccountControllerDelegate;
  @MockitoBean UserConsultantControllerDelegate userConsultantControllerDelegate;
  @MockitoBean UserSupportControllerDelegate userSupportControllerDelegate;
  @MockitoBean UserTwoFactorAuthControllerDelegate userTwoFactorAuthControllerDelegate;
  @MockitoBean UserChatControllerDelegate userChatControllerDelegate;
  @MockitoBean CreateUserFacade createUserFacade;
  @MockitoBean CreateNewSessionFacade createNewSessionFacade;
  @MockitoBean ConsultantDataFacade consultantDataFacade;
  @MockitoBean SessionDataService sessionDataService;
  @MockitoBean SessionArchiveService sessionArchiveService;
  @MockitoBean IdentityManaging identityManager;
  @MockitoBean AccountManaging accountManager;
  @MockitoBean Messaging messenger;
  @MockitoBean ConsultantDtoMapper consultantDtoMapper;
  @MockitoBean UserDtoMapper userDtoMapper;
  @MockitoBean ConsultantService consultantService;
  @MockitoBean ConsultantPublicSlugService consultantPublicSlugService;
  @MockitoBean ConsultantUpdateService consultantUpdateService;
  @MockitoBean ConsultantDataProvider consultantDataProvider;
  @MockitoBean AskerDataProvider askerDataProvider;
  @MockitoBean VideoChatConfig videoChatConfig;
  @MockitoBean KeycloakUserDataProvider keycloakUserDataProvider;
  @MockitoBean MagicLinkLoginService magicLinkLoginService;
  @MockitoBean ConsultantAgencyService consultantAgencyService;
  @MockitoBean AdminUserFacade adminUserFacade;
  @MockitoBean SessionDeleteService sessionDeleteService;
  @MockitoBean EventNotificationService eventNotificationService;
  @MockitoBean UsernameTranscoder usernameTranscoder;
  @MockitoBean ChatOccurrenceQueryService chatOccurrenceQueryService;
  @MockitoBean ChatOccurrenceCommandService chatOccurrenceCommandService;
  @MockitoBean GroupChatRoleService groupChatRoleService;
  @MockitoBean AuthenticatedUser authenticatedUser;

  @Test
  void rawJsonBodyIsRejectedBeforeDelegate() throws Exception {
    mvc.perform(
            put("/users/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("synthetic@example.test"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(userAccountControllerDelegate);
  }

  @Test
  void quotedJsonBindsExactUnquotedEmail() throws Exception {
    when(userAccountControllerDelegate.updateEmailAddress("synthetic@example.test"))
        .thenReturn(ResponseEntity.ok().build());
    mvc.perform(
            put("/users/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"synthetic@example.test\""))
        .andExpect(status().isOk());
    verify(userAccountControllerDelegate).updateEmailAddress("synthetic@example.test");
    verifyNoMoreInteractions(userAccountControllerDelegate);
    assertThat(
            adapter.getMessageConverters().stream()
                .filter(c -> c.canRead(String.class, MediaType.APPLICATION_JSON))
                .findFirst()
                .orElseThrow())
        .isInstanceOf(JacksonJsonHttpMessageConverter.class);
    System.out.println(
        "EMAIL_WIRE_READERS="
            + adapter.getMessageConverters().stream()
                .filter(c -> c.canRead(String.class, MediaType.APPLICATION_JSON))
                .map(c -> c.getClass().getSimpleName())
                .toList());
  }
}
