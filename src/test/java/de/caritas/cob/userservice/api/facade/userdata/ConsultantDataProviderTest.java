package de.caritas.cob.userservice.api.facade.userdata;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT_WITH_AGENCY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.GRANTED_AUTHORIZATION_CONSULTANT_DEFAULT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hibernate.validator.internal.util.CollectionHelper.asSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
public class ConsultantDataProviderTest {

  private static final String PRIVATE_DIAGNOSTIC = "synthetic-private-diagnostic";
  private final EasyRandom easyRandom = new EasyRandom();

  @InjectMocks private ConsultantDataProvider underTest;

  @Mock private AgencyService agencyService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private ConsultingTypeManager consultingTypeManager;

  @Mock private SessionRepository sessionRepository;

  @Mock private EmailNotificationMapper emailNotificationMapper;

  @Test
  public void retrieveData_Should_ReturnAPlannedAbsence_AsNotAbsentYetWithItsDays() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(new HashSet<>());
    var today = java.time.LocalDate.now(Consultant.ABSENCE_ZONE);
    consultant.setAbsent(true);
    consultant.setAbsenceStart(today.plusDays(3));
    consultant.setAbsenceEnd(today.plusDays(10));
    when(agencyService.getAgencies(any())).thenReturn(List.of());

    var result = underTest.retrieveData(consultant);

    assertEquals(false, result.isAbsent());
    assertEquals(today.plusDays(3), result.getAbsenceStart());
    assertEquals(today.plusDays(10), result.getAbsenceEnd());
  }

  @Test
  public void retrieveData_Should_DropTheDays_OfAnAbsenceThatIsOver() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(new HashSet<>());
    var today = java.time.LocalDate.now(Consultant.ABSENCE_ZONE);
    consultant.setAbsent(true);
    consultant.setAbsenceStart(today.minusDays(10));
    consultant.setAbsenceEnd(today.minusDays(1));
    when(agencyService.getAgencies(any())).thenReturn(List.of());

    var result = underTest.retrieveData(consultant);

    assertEquals(false, result.isAbsent());
    assertNull(result.getAbsenceStart());
    assertNull(result.getAbsenceEnd());
  }

  @Test
  public void retrieveData_Should_FlattenTheAvatarChoice() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setAvatarKind(ConsultantAvatarKind.ICON);
    consultant.setAvatarId("motif-24");
    when(agencyService.getAgencies(any())).thenReturn(List.of());

    var result = underTest.retrieveData(consultant);

    assertEquals("ICON", result.getAvatarKind());
    assertEquals("motif-24", result.getAvatarId());
  }

  @Test
  public void retrieveData_Should_ReturnNullAvatarKind_When_NoChoiceWasMade() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setAvatarKind(null);
    consultant.setAvatarId(null);
    when(agencyService.getAgencies(any())).thenReturn(List.of());

    var result = underTest.retrieveData(consultant);

    assertNull(result.getAvatarKind());
    assertNull(result.getAvatarId());
  }

  @Test
  public void retrieveData_Should_ReturnMinimalProfile_When_NoAgenciesFound() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setChatRecoveryMode("LOGIN_PASSWORD");
    consultant.setChatRecoveryPolicyRevision(7L);
    consultant.setConsultantAgencies(new HashSet<>());
    when(agencyService.getAgencies(any())).thenReturn(List.of());

    var result = underTest.retrieveData(consultant);

    assertEquals("LOGIN_PASSWORD", result.getChatRecoveryMode());
    assertEquals(7L, result.getChatRecoveryPolicyRevision());
    assertNotNull(result);
    assertEquals(consultant.getId(), result.getUserId());
    assertTrue(result.getAgencies().isEmpty());
    assertFalse(result.isHasAnonymousConversations());
    Mockito.verifyNoInteractions(consultingTypeManager);
  }

  @Test
  public void
      retrieveData_Should_ReturnUserDataResponseDTOWithAgencyDTO_When_ProvidedWithCorrectConsultant() {
    when(authenticatedUser.getRoles()).thenReturn(asSet(UserRole.CONSULTANT.getValue()));
    when(agencyService.getAgencies(any())).thenReturn(List.of(AGENCY_DTO_SUCHT));

    List<AgencyDTO> result = underTest.retrieveData(CONSULTANT_WITH_AGENCY).getAgencies();

    assertNotNull(result);
    assertEquals(AGENCY_DTO_SUCHT, result.get(0));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void retrieveData_Should_ReturnMinimalProfile_When_AgencyServiceReturnsServerError(
      boolean withBody) {
    when(agencyService.getAgencies(any()))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "server error",
                null,
                withBody ? PRIVATE_DIAGNOSTIC.getBytes(StandardCharsets.UTF_8) : null,
                StandardCharsets.UTF_8));

    try (var logs = LogbackCaptor.forClass(ConsultantDataProvider.class)) {
      var result = underTest.retrieveData(CONSULTANT_WITH_AGENCY);

      assertEquals(CONSULTANT_WITH_AGENCY.getId(), result.getUserId());
      assertTrue(result.getAgencies().isEmpty());
      assertFalse(result.isHasAnonymousConversations());
      Mockito.verifyNoInteractions(consultingTypeManager);
      assertEquals(1L, logs.count(Level.WARN));
      var message = logs.messages(Level.WARN).getFirst();
      assertTrue(message.contains("500"));
      assertFalse(message.contains(PRIVATE_DIAGNOSTIC));
      assertFalse(message.contains(CONSULTANT_WITH_AGENCY.getId()));
      assertNull(logs.events().getFirst().getThrowableProxy());
    }
  }

  @Test
  void agencyTransportFailureKeepsMinimalProfileWithoutLoggingExceptionDetails() {
    when(agencyService.getAgencies(any()))
        .thenThrow(new ResourceAccessException(PRIVATE_DIAGNOSTIC));

    try (var logs = LogbackCaptor.forClass(ConsultantDataProvider.class)) {
      var result = underTest.retrieveData(CONSULTANT_WITH_AGENCY);

      assertEquals(CONSULTANT_WITH_AGENCY.getId(), result.getUserId());
      assertTrue(result.getAgencies().isEmpty());
      assertFalse(result.isHasAnonymousConversations());
      assertEquals(1L, logs.count(Level.WARN));
      assertNull(logs.events().getFirst().getThrowableProxy());
      assertFalse(logs.events().getFirst().getFormattedMessage().contains(PRIVATE_DIAGNOSTIC));
    }
  }

  @Test
  void consultingTypeFailureDisablesAnonymousConversationsWithoutLoggingExceptionDetails() {
    when(agencyService.getAgencies(any())).thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenThrow(new ResourceAccessException(PRIVATE_DIAGNOSTIC));

    try (var logs = LogbackCaptor.forClass(ConsultantDataProvider.class)) {
      assertFalse(underTest.retrieveData(CONSULTANT_WITH_AGENCY).isHasAnonymousConversations());
      assertEquals(1L, logs.count(Level.WARN));
      assertNull(logs.events().getFirst().getThrowableProxy());
      assertFalse(logs.events().getFirst().getFormattedMessage().contains(PRIVATE_DIAGNOSTIC));
    }
  }

  @Test
  public void retrieveData_Should_ReturnMinimalProfile_When_AgencyServiceReturnsNotFound() {
    when(agencyService.getAgencies(any()))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    var result = underTest.retrieveData(CONSULTANT_WITH_AGENCY);

    assertEquals(CONSULTANT_WITH_AGENCY.getId(), result.getUserId());
    assertTrue(result.getAgencies().isEmpty());
    assertFalse(result.isHasAnonymousConversations());
    Mockito.verifyNoInteractions(consultingTypeManager);
  }

  @Test
  public void retrieveData_Should_ReturnValidData() {
    when(agencyService.getAgencies(any())).thenReturn(List.of(AGENCY_DTO_SUCHT));

    when(authenticatedUser.getGrantedAuthorities())
        .thenReturn(asSet(GRANTED_AUTHORIZATION_CONSULTANT_DEFAULT));
    when(authenticatedUser.getRoles()).thenReturn(asSet(UserRole.CONSULTANT.toString()));
    when(sessionRepository.countByConsultantAndStatusInAndRegistrationType(any(), any(), any()))
        .thenReturn(5L);

    UserDataResponseDTO result = underTest.retrieveData(CONSULTANT_WITH_AGENCY);

    assertEquals(CONSULTANT_WITH_AGENCY.getId(), result.getUserId());
    assertEquals(CONSULTANT_WITH_AGENCY.getUsername(), result.getUserName());
    assertEquals(CONSULTANT_WITH_AGENCY.getFirstName(), result.getFirstName());
    assertEquals(CONSULTANT_WITH_AGENCY.getLastName(), result.getLastName());
    assertEquals(CONSULTANT_WITH_AGENCY.getEmail(), result.getEmail());
    assertNull(result.getConsultingTypes());
    assertEquals(CONSULTANT_WITH_AGENCY.isLanguageFormal(), result.isFormalLanguage());
    assertEquals(
        Set.of(CONSULTANT_WITH_AGENCY.getLanguages().iterator().next().getLanguageCode().name()),
        result.getLanguages());
    assertEquals(CONSULTANT_WITH_AGENCY.isAbsent(), result.isAbsent());
    assertEquals(CONSULTANT_WITH_AGENCY.isTeamConsultant(), result.isInTeamAgency());
    assertEquals(
        GRANTED_AUTHORIZATION_CONSULTANT_DEFAULT,
        result.getGrantedAuthorities().stream().findFirst().orElse(null));
    assertEquals(
        UserRole.CONSULTANT.toString(), result.getUserRoles().stream().findFirst().orElse(null));
    assertEquals(AGENCY_DTO_SUCHT, result.getAgencies().get(0));
    assertTrue(result.isHasArchive());
  }

  @Test
  public void
      retrieveData_Should_returnDataWithAnonymousConversationsTrue_When_consultantHasAtLeastOneConsultingTypeWithAnonymousConversationsAllowed() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    when(this.agencyService.getAgencies(any()))
        .thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(this.consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().isAnonymousConversationAllowed(true));

    var result = underTest.retrieveData(consultant);

    assertThat(result.isHasAnonymousConversations(), is(true));
  }

  @Test
  public void
      retrieveData_Should_returnDataWithAnonymousConversationsFalse_When_allConsultingTypesHaveAnonymousConversationsDisabled() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    when(this.agencyService.getAgencies(any()))
        .thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(this.consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().isAnonymousConversationAllowed(false));

    var result = underTest.retrieveData(consultant);

    assertThat(result.isHasAnonymousConversations(), is(false));
  }

  @Test
  public void
      retrieveData_Should_returnDataWithHasArchiveTrue_When_ConsultantHasRegisteredSessions() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setTeamConsultant(false);
    when(this.agencyService.getAgencies(any()))
        .thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(this.consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().isAnonymousConversationAllowed(false));
    when(sessionRepository.countByConsultantAndStatusInAndRegistrationType(any(), any(), any()))
        .thenReturn(5L);

    var result = underTest.retrieveData(consultant);

    assertTrue(result.isHasArchive());
  }

  @Test
  public void
      retrieveData_Should_returnDataWithHasArchiveFalse_When_ConsultantHasNoRegisteredSessions() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setTeamConsultant(false);
    when(this.agencyService.getAgencies(any()))
        .thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(this.consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().isAnonymousConversationAllowed(false));
    when(sessionRepository.countByConsultantAndStatusInAndRegistrationType(any(), any(), any()))
        .thenReturn(0L);

    var result = underTest.retrieveData(consultant);

    assertFalse(result.isHasArchive());
  }

  @Test
  public void
      retrieveData_Should_returnDataWithHasArchiveTrue_When_ConsultantHasNoRegisteredSessionsButIsTeamConsultant() {
    Consultant consultant = easyRandom.nextObject(Consultant.class);
    consultant.setTeamConsultant(true);
    when(this.agencyService.getAgencies(any()))
        .thenReturn(List.of(new AgencyDTO().consultingType(1)));
    when(this.consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().isAnonymousConversationAllowed(false));
    when(sessionRepository.countByConsultantAndStatusInAndRegistrationType(any(), any(), any()))
        .thenReturn(5L);

    var result = underTest.retrieveData(consultant);

    assertTrue(result.isHasArchive());
  }

  @Test
  public void retrieveDataShouldReturnConsultantLanguages() {
    var consultant = easyRandom.nextObject(Consultant.class);
    var languages =
        consultant.getLanguages().stream()
            .map(Language::getLanguageCode)
            .map(LanguageCode::name)
            .collect(Collectors.toSet());

    var result = underTest.retrieveData(consultant);

    assertEquals(languages, result.getLanguages());
  }
}
