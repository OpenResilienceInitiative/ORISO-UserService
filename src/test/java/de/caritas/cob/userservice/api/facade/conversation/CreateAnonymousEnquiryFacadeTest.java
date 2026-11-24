package de.caritas.cob.userservice.api.facade.conversation;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_SUCHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade.CreatedAccount;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class CreateAnonymousEnquiryFacadeTest {

  private CreateAnonymousEnquiryFacade createAnonymousEnquiryFacade;
  @Mock private CreateUserFacade createUserFacade;
  @Mock private AnonymousUsernameRegistry usernameRegistry;
  @Mock private UserHelper userHelper;
  @Mock private ConsultingTypeManager consultingTypeManager;

  @org.junit.jupiter.api.BeforeEach
  void realDpaPolicyFixture() {
    createAnonymousEnquiryFacade =
        new CreateAnonymousEnquiryFacade(
            createUserFacade,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            usernameRegistry,
            userHelper,
            consultingTypeManager,
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class));
  }

  EasyRandom easyRandom = new EasyRandom();

  @Test
  void pausedTenantStopsAnonymousCreationBeforeIdentityRowsUsernameOrConversationWork() {
    ReflectionTestUtils.setField(
        createAnonymousEnquiryFacade,
        "tenantService",
        de.caritas.cob.userservice.api.testHelper.PausedTenantAuthorityFixture.paused(79L));
    TenantContext.setCurrentTenant(79L);
    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                createAnonymousEnquiryFacade.createAnonymousEnquiry(
                    new CreateAnonymousEnquiryDTO(1), false));
    org.junit.jupiter.api.Assertions.assertEquals(
        org.springframework.http.HttpStatus.FORBIDDEN, refused.getStatusCode());
    org.mockito.Mockito.verifyNoInteractions(
        createUserFacade, usernameRegistry, userHelper, consultingTypeManager);
  }

  @Test
  void createAnonymousEnquiry_Should_CreateTheHandleTheGuestRolled_When_OneIsChosen() {
    when(usernameRegistry.claimChosenUsername("katze_sam_4821")).thenReturn("enc.katze");
    givenTheEntryCreatesAGuest();

    var response =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(
            new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true, "katze_sam_4821");

    var created =
        org.mockito.ArgumentCaptor.forClass(
            de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.class);
    verify(createUserFacade)
        .createUserAccountWithInitializedConsultingType(
            created.capture(), eq(RegistrationType.ANONYMOUS));
    assertEquals("enc.katze", created.getValue().getUsername());
    assertEquals("enc.katze", response.getUserName());
    verify(usernameRegistry, org.mockito.Mockito.never()).generateUniqueUsername();
  }

  @Test
  void createAnonymousEnquiry_Should_CreateNothing_When_TheChosenHandleIsRefused() {
    when(usernameRegistry.claimChosenUsername("max_mustermann"))
        .thenThrow(new BadRequestException("typed"));

    assertThrows(
        BadRequestException.class,
        () ->
            createAnonymousEnquiryFacade.createAnonymousEnquiry(
                new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true, "max_mustermann"));
    verifyNoInteractions(createUserFacade);
  }

  @Test
  void createAnonymousEnquiry_Should_LetTheServerRoll_When_NoHandleIsChosen() {
    when(usernameRegistry.generateUniqueUsername()).thenReturn("enc.eule");
    givenTheEntryCreatesAGuest();

    var response =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(
            new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true, null);

    assertEquals("enc.eule", response.getUserName());
    verify(usernameRegistry, org.mockito.Mockito.never()).claimChosenUsername(any());
  }

  @Test
  void createAnonymousEnquiry_Should_ReturnTheTokensAndSessionOfTheCreatedGuest() {
    when(usernameRegistry.generateUniqueUsername()).thenReturn("enc.eule");
    when(createUserFacade.createUserAccountWithInitializedConsultingType(
            any(), eq(RegistrationType.ANONYMOUS)))
        .thenReturn(
            new CreatedAccount(
                "user-id", 42L, new IdentityLogin("access-token", 300, 600, "refresh-token")));

    var response =
        createAnonymousEnquiryFacade.createAnonymousEnquiry(
            new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT), true);

    assertEquals("access-token", response.getAccessToken());
    assertEquals("refresh-token", response.getRefreshToken());
    assertEquals(300, response.getExpiresIn());
    assertEquals(600, response.getRefreshExpiresIn());
    assertEquals(42L, response.getSessionId());
    assertEquals("enc.eule", response.getUserName());
  }

  @Test
  public void
      createAnonymousEnquiry_Should_ThrowBadRequestException_When_GivenConsultingTypeDoesNotSupportAnonymousConversations() {
    CreateAnonymousEnquiryDTO anonymousEnquiryDTO =
        easyRandom.nextObject(CreateAnonymousEnquiryDTO.class);
    anonymousEnquiryDTO.setConsultingType(CONSULTING_TYPE_ID_KREUZBUND);
    var consultingTypeResponseDTO = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponseDTO.setIsAnonymousConversationAllowed(false);
    when(consultingTypeManager.getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType()))
        .thenReturn(consultingTypeResponseDTO);

    assertThrows(
        BadRequestException.class,
        () -> createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO, false));

    verifyNoInteractions(createUserFacade);
    verifyNoInteractions(usernameRegistry);
    verifyNoInteractions(userHelper);
  }

  @Test
  void createAnonymousEnquiry_Should_CreateTheGuestOnce_When_TheConsultingTypeAllowsGuests() {
    CreateAnonymousEnquiryDTO anonymousEnquiryDTO = anonymousEnquiry();
    givenTheEntryCreatesAGuest();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiryDTO, false);

    verify(createUserFacade, times(1))
        .createUserAccountWithInitializedConsultingType(any(), eq(RegistrationType.ANONYMOUS));
    verify(consultingTypeManager, times(1))
        .getConsultingTypeSettings(anonymousEnquiryDTO.getConsultingType());
  }

  // --- which tenant the enquiry is written in ---------------------------------------------------

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createAnonymousEnquiry_Should_WriteInTheCallersTenant_When_OneIsSet() {
    TenantContext.setCurrentTenant(5L);
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry(), false);

    assertEquals(5L, writtenIn.get());
  }

  @Test
  void createAnonymousEnquiry_Should_Proceed_When_SingleTenantDeploymentHasNoTenant() {
    TenantContext.clear();
    var writtenIn = givenCreationRecordsItsTenant();

    createAnonymousEnquiryFacade.createAnonymousEnquiry(anonymousEnquiry(), false);

    assertNull(writtenIn.get());
    verify(createUserFacade)
        .createUserAccountWithInitializedConsultingType(any(), eq(RegistrationType.ANONYMOUS));
  }

  private CreateAnonymousEnquiryDTO anonymousEnquiry() {
    var dto = new CreateAnonymousEnquiryDTO(CONSULTING_TYPE_ID_SUCHT);
    var settings = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    settings.setIsAnonymousConversationAllowed(true);
    when(consultingTypeManager.getConsultingTypeSettings(CONSULTING_TYPE_ID_SUCHT))
        .thenReturn(settings);
    return dto;
  }

  private static final CreatedAccount GUEST =
      new CreatedAccount("user-id", 42L, new IdentityLogin("access", 300, 600, "refresh"));

  private void givenTheEntryCreatesAGuest() {
    when(createUserFacade.createUserAccountWithInitializedConsultingType(
            any(), eq(RegistrationType.ANONYMOUS)))
        .thenReturn(GUEST);
  }

  private AtomicReference<Long> givenCreationRecordsItsTenant() {
    var writtenIn = new AtomicReference<Long>();
    when(createUserFacade.createUserAccountWithInitializedConsultingType(
            any(), eq(RegistrationType.ANONYMOUS)))
        .thenAnswer(
            call -> {
              writtenIn.set(TenantContext.getCurrentTenant());
              return GUEST;
            });
    return writtenIn;
  }
}
