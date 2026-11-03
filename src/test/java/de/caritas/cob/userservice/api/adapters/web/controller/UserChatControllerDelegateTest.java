package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalSubsetChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.JsonNullableJacksonConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.AssignChatFacade;
import de.caritas.cob.userservice.api.facade.CreateChatFacade;
import de.caritas.cob.userservice.api.facade.GetChatFacade;
import de.caritas.cob.userservice.api.facade.GetChatMembersFacade;
import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade;
import de.caritas.cob.userservice.api.facade.JoinAndLeaveChatFacade;
import de.caritas.cob.userservice.api.facade.StartChatFacade;
import de.caritas.cob.userservice.api.facade.StopChatFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.service.chat.GroupChatFeatureGate;
import de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

@ExtendWith(MockitoExtension.class)
class UserChatControllerDelegateTest {

  @Mock private UserAccountService userAccountProvider;
  @Mock private ChatService chatService;
  @Mock private StartChatFacade startChatFacade;
  @Mock private GetChatFacade getChatFacade;
  @Mock private JoinAndLeaveChatFacade joinAndLeaveChatFacade;
  @Mock private AssignChatFacade assignChatFacade;
  @Mock private CreateChatFacade createChatFacade;
  @Mock private InternalSubsetChatFacade internalSubsetChatFacade;
  @Mock private StopChatFacade stopChatFacade;
  @Mock private GetChatMembersFacade getChatMembersFacade;
  @Mock private AccountManaging accountManager;
  @Mock private Messaging messenger;
  @Mock private UserDtoMapper userDtoMapper;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private GroupChatFeatureGate groupChatFeatureGate;
  @Mock private GroupChatPermissionService groupChatPermissionService;

  @InjectMocks private UserChatControllerDelegate delegate;

  @Test
  void createChatV1ShouldReturnCreatedResponseFromFacade() {
    var chatDTO = new ChatDTO();
    var consultant = consultant();
    var createChatResponseDTO = new CreateChatResponseDTO();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(createChatFacade.createChatV1(chatDTO, consultant)).thenReturn(createChatResponseDTO);

    var response = delegate.createChatV1(chatDTO);

    verify(groupChatFeatureGate).requireEnabled(consultant, chatDTO);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(createChatResponseDTO);
  }

  @Test
  void createChatV2ShouldReturnCreatedResponseFromFacade() {
    var chatDTO = new ChatDTO();
    var consultant = consultant();
    var createChatResponseDTO = new CreateChatResponseDTO();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(createChatFacade.createChatV2(chatDTO, consultant)).thenReturn(createChatResponseDTO);

    var response = delegate.createChatV2(chatDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(createChatResponseDTO);
    verify(groupChatFeatureGate).requireEnabled(consultant, chatDTO);
  }

  @Test
  void openInternalSubsetChatShouldAnswerCreatedForANewRoomAndOkForAReusedOne() {
    var consultant = consultant();
    var request = new InternalSubsetChatDTO().consultantIds(java.util.List.of("colleague"));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(internalSubsetChatFacade.open(request, consultant))
        .thenReturn(new InternalSubsetChatFacade.OpenedRoom("!new:server", 7L, true))
        .thenReturn(new InternalSubsetChatFacade.OpenedRoom("!new:server", 7L, false));

    var created = delegate.openInternalSubsetChat(request);
    var reused = delegate.openInternalSubsetChat(request);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(created.getBody().getMatrixRoomId()).isEqualTo("!new:server");
    assertThat(created.getBody().getChatId()).isEqualTo(7L);
    assertThat(reused.getBody()).isEqualTo(created.getBody());
  }

  @Test
  void startChatShouldStartExistingChatAndReturnOk() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.startChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(startChatFacade).startChat(chat, consultant);
  }

  @Test
  void startChatShouldThrowBadRequestWhenChatDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.startChat(1L)).isInstanceOf(BadRequestException.class);

    verify(chatService).getChat(1L);
  }

  @Test
  void getChatShouldReturnFacadeResponse() {
    var chatInfoResponseDTO = new ChatInfoResponseDTO();
    when(getChatFacade.getChat(1L)).thenReturn(chatInfoResponseDTO);

    var response = delegate.getChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatInfoResponseDTO);
  }

  @Test
  void assignChatShouldDelegateAndReturnOk() {
    var response = delegate.assignChat("!group:matrix.example", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignChatFacade).assignChat("!group:matrix.example", authenticatedUser);
  }

  @Test
  void assignChatDelegatesStableNumericSeriesIdentifier() {
    var response = delegate.assignChat("1013", "link-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignChatFacade).assignChat(1013L, "link-token", authenticatedUser);
  }

  @Test
  void assignChatRejectsNumericSeriesIdentifierAboveLongRange() {
    assertThatThrownBy(() -> delegate.assignChat("9223372036854775808", null))
        .isInstanceOf(BadRequestException.class);

    verify(assignChatFacade, never()).assignChat(anyLong(), any(), any());
  }

  @Test
  void joinChatShouldDelegateAndReturnOk() {
    var response = delegate.joinChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).joinChat(1L, authenticatedUser);
  }

  @Test
  void verifyCanModerateChatShouldDelegateAndReturnOk() {
    var response = delegate.verifyCanModerateChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).verifyCanModerate(1L);
  }

  @Test
  void stopChatShouldStopAndReturnOk() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.stopChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(stopChatFacade).stopChat(chat, consultant);
  }

  @Test
  void stopChatShouldThrowBadRequestWhenChatDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.stopChat(1L)).isInstanceOf(BadRequestException.class);

    verify(chatService).getChat(1L);
  }

  @Test
  void getChatMembersShouldReturnOkWithFacadeResponse() {
    var chatMembersResponseDTO = new ChatMembersResponseDTO();
    when(getChatMembersFacade.getChatMembers(1L)).thenReturn(chatMembersResponseDTO);

    var response = delegate.getChatMembers(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatMembersResponseDTO);
  }

  @Test
  void leaveChatShouldDelegateAndReturnOk() {
    var response = delegate.leaveChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).leaveChat(1L, authenticatedUser);
  }

  @Test
  void updateChatShouldReturnOkWithServiceResponse() {
    var chatDTO = new ChatDTO();
    var updateChatResponseDTO = new UpdateChatResponseDTO();
    when(chatService.updateChat(1L, chatDTO, authenticatedUser)).thenReturn(updateChatResponseDTO);

    var response = delegate.updateChat(1L, chatDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(updateChatResponseDTO);
  }

  @Test
  void banFromChatShouldBanAdviceSeekerAndReturnNoContent() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(accountManager.findAdviceSeekerByMatrixUserId("chat-user-id"))
        .thenReturn(Optional.of(adviceSeeker()));
    when(messenger.banUserFromChat("advice-seeker-id", 1L)).thenReturn(true);

    var response = delegate.banFromChat("chat-user-id", 1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(groupChatPermissionService).requireCanModerate(chat, consultant);
    verify(messenger).banUserFromChat("advice-seeker-id", 1L);
  }

  @Test
  void banFromChatShouldRefuseANonModeratorBeforeLookingUpTheMatrixUser() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    doThrow(new ForbiddenException("not a moderator"))
        .when(groupChatPermissionService)
        .requireCanModerate(chat, consultant);

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(ForbiddenException.class);

    verify(accountManager, never()).findAdviceSeekerByMatrixUserId(any());
    verify(messenger, never()).banUserFromChat(any(), anyLong());
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenAdviceSeekerDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat()));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant());
    when(accountManager.findAdviceSeekerByMatrixUserId("chat-user-id"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
    verify(messenger, never()).banUserFromChat(any(), anyLong());
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenChatDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
    verify(accountManager, never()).findAdviceSeekerByMatrixUserId(any());
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenBanFails() {
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat()));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant());
    when(accountManager.findAdviceSeekerByMatrixUserId("chat-user-id"))
        .thenReturn(Optional.of(adviceSeeker()));
    when(messenger.banUserFromChat(any(), anyLong())).thenReturn(false);

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "2,false,SUGGESTED,true,false,42,7,true,valid",
    "2,false,ENFORCED,true,true,42,7,false,valid",
    "2,true,ENFORCED,false,false,42,7,true,valid",
    "2,true,SUGGESTED,false,true,42,7,false,valid",
    "2,true,SUGGESTED,true,false,42,7,true,valid",
    "2,false,SUGGESTED,false,false,42,7,false,valid",
    "2,false,SUGGESTED,true,false,99,7,false,valid",
    "2,false,SUGGESTED,true,false,42,8,false,valid",
    "1,false,ENFORCED,true,true,42,7,false,valid",
    "1,false,SUGGESTED,true,false,42,7,true,valid",
    "1,true,SUGGESTED,false,true,42,7,false,valid",
    "2,true,SUGGESTED,true,true,42,7,false,invalidJson",
    "2,true,SUGGESTED,true,true,42,7,false,invalidBoolean",
    "1,true,SUGGESTED,true,true,42,7,false,wrongContentType"
  })
  void createChatUsesFreshGeneratedPublicClientsAndRejectsForeignProjections(
      int version,
      boolean parentValue,
      String mode,
      boolean agencyValue,
      boolean oldMaster,
      long projectionId,
      long projectionTenant,
      boolean allowed,
      String responseKind) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                org.springframework.boot.http.converter.autoconfigure
                    .HttpMessageConvertersAutoConfiguration.class,
                RestTemplateAutoConfiguration.class))
        .withUserConfiguration(JsonNullableJacksonConfig.class)
        .run(
            ctx -> {
              var rest = ctx.getBean(RestTemplateBuilder.class).build();
              var http = MockRestServiceServer.bindTo(rest).build();
              http.expect(requestTo("http://tenant/tenant/public/id/7"))
                  .andRespond(
                      org.springframework.test.web.client.response.MockRestResponseCreators
                          .withSuccess(
                              "{\"id\":7,\"name\":\"Tenant\",\"settings\":{\"featureGroupChatV2Enabled\":"
                                  + oldMaster
                                  + "},\"permissionPolicies\":{\"featureSelfHelpGroupsEnabled\":{\"value\":"
                                  + parentValue
                                  + ",\"mode\":\""
                                  + mode
                                  + "\",\"inherited\":true}}}",
                              MediaType.APPLICATION_JSON));
              String agencyBody =
                  "[{\"id\":"
                      + projectionId
                      + ",\"tenantId\":"
                      + projectionTenant
                      + ",\"settings\":{\"featureGroupChatV2Enabled\":false,\"featureSelfHelpGroupsEnabled\":"
                      + agencyValue
                      + "}}]";
              if (responseKind.equals("invalidJson")) agencyBody = "{invalid-json";
              if (responseKind.equals("invalidBoolean"))
                agencyBody =
                    "[{\"id\":42,\"tenantId\":7,\"settings\":{\"featureSelfHelpGroupsEnabled\":\"not-a-boolean\"}}]";
              http.expect(ExpectedCount.between(0, 1), requestTo("http://agency/agencies/42"))
                  .andRespond(
                      withSuccess(
                          agencyBody,
                          responseKind.equals("wrongContentType")
                              ? MediaType.TEXT_PLAIN
                              : MediaType.APPLICATION_JSON));
              var tenantFactory =
                  new de.caritas.cob.userservice.api.config.apiclient
                      .TenantServiceApiControllerFactory();
              ReflectionTestUtils.setField(tenantFactory, "restTemplate", rest);
              ReflectionTestUtils.setField(tenantFactory, "tenantServiceApiUrl", "http://tenant");
              var tenantService =
                  new TenantService(tenantFactory, Mockito.mock(CacheManager.class));
              var agencyFactory =
                  new de.caritas.cob.userservice.api.config.apiclient
                      .AgencyServiceApiControllerFactory();
              ReflectionTestUtils.setField(agencyFactory, "restTemplate", rest);
              ReflectionTestUtils.setField(agencyFactory, "agencyServiceApiUrl", "http://agency");
              var security = Mockito.mock(SecurityHeaderSupplier.class);
              if (parentValue || !"ENFORCED".equals(mode)) {
                when(security.getOptionalKeycloakAndCsrfHttpHeaders())
                    .thenReturn(new HttpHeaders());
              }
              var lookup =
                  new EffectiveAgencySettingsLookup(
                      agencyFactory, security, Mockito.mock(TenantHeaderSupplier.class));
              var realGate = new GroupChatFeatureGate(tenantService, lookup);
              ReflectionTestUtils.setField(delegate, "groupChatFeatureGate", realGate);
              var actor = consultant();
              actor.setTenantId(7L);
              when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(actor);
              var request =
                  ChatDTO.builder().topic("Circle").agencyId(42L).repetitive(true).build();
              if (allowed) {
                if (version == 1)
                  when(createChatFacade.createChatV1(request, actor))
                      .thenReturn(new CreateChatResponseDTO());
                else
                  when(createChatFacade.createChatV2(request, actor))
                      .thenReturn(new CreateChatResponseDTO());
                var response =
                    version == 1 ? delegate.createChatV1(request) : delegate.createChatV2(request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
              } else {
                var refusal =
                    assertThatThrownBy(
                        () -> {
                          if (version == 1) delegate.createChatV1(request);
                          else delegate.createChatV2(request);
                        });
                if (responseKind.equals("valid")) refusal.isInstanceOf(ForbiddenException.class);
                else
                  refusal
                      .isInstanceOf(
                          de.caritas.cob.userservice.api.exception.httpresponses
                              .ServiceUnavailableException.class)
                      .hasMessage("Agency policy projection is unreadable");
                verify(createChatFacade, never()).createChatV1(any(), any());
                verify(createChatFacade, never()).createChatV2(any(), any());
              }
              http.verify();
            });
  }

  private Chat chat() {
    var now = LocalDateTime.now();
    return Chat.builder().id(1L).topic("topic").initialStartDate(now).startDate(now).build();
  }

  private User adviceSeeker() {
    return User.builder()
        .userId("advice-seeker-id")
        .username("advice-seeker")
        .email("advice-seeker@example.com")
        .build();
  }

  private Consultant consultant() {
    return Consultant.builder()
        .id("consultant-id")
        .matrixUserId("@member:matrix.example")
        .username("consultant")
        .firstName("Con")
        .lastName("Sultant")
        .email("consultant@example.com")
        .build();
  }
}
