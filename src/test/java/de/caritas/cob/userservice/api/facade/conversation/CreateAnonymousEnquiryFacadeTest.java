package de.caritas.cob.userservice.api.facade.conversation;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_SUCHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUserCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class CreateAnonymousEnquiryFacadeTest {

  @InjectMocks private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @Mock private AnonymousUserCreatorService anonymousUserCreatorService;
  @Mock private AnonymousConversationCreatorService anonymousConversationCreatorService;
  @Mock private AnonymousUsernameRegistry usernameRegistry;
  @Mock private UserHelper userHelper;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Spy private TenantContextProvider tenantContextProvider = new TenantContextProvider();

  EasyRandom easyRandom = new EasyRandom();

  @Test
  void createAnonymousEnquiry_Should_ReturnMatrixOnlyResponse() {
    CreateAnonymousEnquiryDTO request = new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT);
    AnonymousUserCredentials credentials =
        AnonymousUserCredentials.builder()
            .userId("user-id")
            .accessToken("access-token")
            .refreshToken("refresh-token")
            .expiresIn(300)
            .refreshExpiresIn(600)
            .build();
    Session session = easyRandom.nextObject(Session.class);
    session.setMatrixRoomId(null);
    when(anonymousUserCreatorService.createAnonymousUser(any())).thenReturn(credentials);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);

    var response = createAnonymousEnquiryFacade.createAnonymousEnquiry(request, true);

    assertEquals("access-token", response.getAccessToken());
    assertEquals(session.getId(), response.getSessionId());
  }

  @Test
  public void
      createAnonymousEnquiry_Should_ThrowBadRequestException_When_GivenConsultingTypeDoesNotSupportAnonymousConversations() {
    assertThrows(
        BadRequestException.class,
        () -> {
          CreateAnonymousEnquiryDTO anonymousEnquiryDTO =
              easyRandom.nextObject(CreateAnonymousEnquiryDTO.class);
          anonymousEnquiryDTO.setConsultingType(CONSULTING_TYPE_ID_KREUZBUND);
          var consultingTypeResponseDTO =
              easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
          consultingTypeResponseDTO.setIsAnonymousConversationAllowed(false);
          when(consultingTypeManager.getConsultingTypeSettings(
                  anonymousEnquiryDTO.getConsultingType()))
              .thenReturn(consultingTypeResponseDTO);

          createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO);

          verifyNoInteractions(anonymousUserCreatorService);
          verifyNoInteractions(anonymousConversationCreatorService);
          verifyNoInteractions(usernameRegistry);
          verifyNoInteractions(userHelper);
        });
  }

  @Test
  public void createAnonymousEnquiry_Should_ReturnValidCreateAnonymousEnquiryResponseDTO() {
    CreateAnonymousEnquiryDTO anonymousEnquiryDTO =
        easyRandom.nextObject(CreateAnonymousEnquiryDTO.class);
    anonymousEnquiryDTO.setConsultingType(CONSULTING_TYPE_ID_SUCHT);
    AnonymousUserCredentials credentials = easyRandom.nextObject(AnonymousUserCredentials.class);
    when(anonymousUserCreatorService.createAnonymousUser(any())).thenReturn(credentials);
    Session session = easyRandom.nextObject(Session.class);
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(session);
    var consultingTypeResponseDTO = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponseDTO.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType()))
        .thenReturn(consultingTypeResponseDTO);

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO);

    verify(anonymousUserCreatorService, times(1)).createAnonymousUser(any());
    verify(anonymousConversationCreatorService, times(1)).createAnonymousConversation(any(), any());
    verify(consultingTypeManager, times(1))
        .getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType());
  }

  // --- which tenant the enquiry is written in ---------------------------------------------------

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createAnonymousEnquiry_Should_WriteNothing_When_MultiTenantRouteHasNoTenant() {
    ReflectionTestUtils.setField(tenantContextProvider, "multiTenancyEnabled", true);
    TenantContext.clear();

    assertThrows(
        ForbiddenException.class,
        () ->
            createAnonymousEnquiryFacade.createAnonymousEnquiry(
                new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT)));

    verifyNoInteractions(anonymousUserCreatorService, anonymousConversationCreatorService);
  }

  @Test
  void createAnonymousEnquiry_Should_WriteInTheCallersTenant_When_OneIsSet() {
    ReflectionTestUtils.setField(tenantContextProvider, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(5L);
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry());

    assertEquals(5L, writtenIn.get());
  }

  @Test
  void createAnonymousEnquiry_Should_Proceed_When_SingleTenantDeploymentHasNoTenant() {
    TenantContext.clear();
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry());

    assertNull(writtenIn.get());
    verify(anonymousUserCreatorService).createAnonymousUser(any());
  }

  private CreateAnonymousEnquiryDTO anonymousEnquiry() {
    var dto = new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT);
    var settings = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    settings.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(CONSULTING_TYPE_ID_SUCHT))
        .thenReturn(settings);
    return dto;
  }

  private AtomicReference<Long> givenCreationRecordsItsTenant() {
    var writtenIn = new AtomicReference<Long>();
    when(anonymousUserCreatorService.createAnonymousUser(any()))
        .thenAnswer(
            call -> {
              writtenIn.set(TenantContext.getCurrentTenant());
              return easyRandom.nextObject(AnonymousUserCredentials.class);
            });
    when(anonymousConversationCreatorService.createAnonymousConversation(any(), any()))
        .thenReturn(easyRandom.nextObject(Session.class));
    return writtenIn;
  }
}
