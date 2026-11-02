package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.SESSION_ID;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.SessionDataProvider;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SessionDataServiceTest {

  @InjectMocks private SessionDataService sessionDataService;
  @Mock private SessionDataRepository sessionDataRepository;
  @Mock private SessionDataProvider sessionDataProvider;
  @Mock private SessionService sessionService;
  @Mock private de.caritas.cob.userservice.api.helper.AuthenticatedUser authenticatedUser;
  @Mock private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenantService;
  @org.mockito.Spy private GeneratedPseudonyms generatedPseudonyms = new GeneratedPseudonyms();

  @org.junit.jupiter.api.BeforeEach
  void defaultIdentityPolicy() {
    org.mockito.Mockito.lenient().when(authenticatedUser.getUserId()).thenReturn("asker");
    org.mockito.Mockito.lenient()
        .when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDisplayNameEditable(true)));
  }

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void sessionWithoutAnAskerRejectsAliasUpdateWithoutAnInternalError() {
    var realAccess =
        org.mockito.Mockito.mock(SessionService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    var repository =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        realAccess, "sessionRepository", repository);
    org.springframework.test.util.ReflectionTestUtils.setField(
        sessionDataService, "sessionService", realAccess);
    var session = new Session();
    when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionDataService.saveSessionData(
                    SESSION_ID, new SessionDataDTO().displayName("katze_mika_1234")))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    org.mockito.Mockito.verifyNoInteractions(sessionDataRepository);
  }

  @Test
  void anonymousOwnerCanSaveAGeneratedAliasThroughTheRealAccessPolicy() {
    var realAccess =
        org.mockito.Mockito.mock(SessionService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    var repository =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        realAccess, "sessionRepository", repository);
    org.springframework.test.util.ReflectionTestUtils.setField(
        sessionDataService, "sessionService", realAccess);
    var session = aliasSession();
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(authenticatedUser.getRoles())
        .thenReturn(
            java.util.Set.of(
                de.caritas.cob.userservice.api.config.auth.UserRole.ANONYMOUS.getValue()));
    sessionDataService.saveSessionData(
        SESSION_ID, new SessionDataDTO().displayName("katze_mika_1234"));
    verify(sessionDataRepository).saveAll(any());
  }

  @Test
  void arbitrarySessionAliasIsRejectedWhenCustomNamesAreDisabled() {
    var user = new de.caritas.cob.userservice.api.model.User();
    user.setUserId("asker");
    user.setTenantId(42L);
    var session = new Session();
    session.setUser(user);
    session.setTenantId(42L);
    when(sessionService.assertAskerHasAccess(SESSION_ID, authenticatedUser)).thenReturn(session);
    when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDisplayNameEditable(false)));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionDataService.saveSessionData(
                    SESSION_ID, new SessionDataDTO().displayName("My arbitrary personal name")))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    org.mockito.Mockito.verifyNoInteractions(sessionDataRepository);
  }

  @Test
  public void saveSessionDataForSessionId_Should_ThrowNotFoundException_When_SessionNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> {
          SessionDataDTO sessionData = easyRandom.nextObject(SessionDataDTO.class);
          when(sessionService.assertAskerHasAccess(any(), any()))
              .thenThrow(new NotFoundException("missing"));

          sessionDataService.saveSessionData(SESSION_ID, sessionData);
        });
  }

  @Test
  public void saveSessionDataForSessionId_Should_SaveSessionData() {
    SessionDataDTO sessionData = easyRandom.nextObject(SessionDataDTO.class);
    Session session = easyRandom.nextObject(Session.class);
    session.getUser().setUserId("asker");
    session.setTenantId(42L);
    when(sessionService.assertAskerHasAccess(any(), any())).thenReturn(session);

    sessionDataService.saveSessionData(SESSION_ID, sessionData);

    verify(sessionDataProvider, times(1)).createSessionDataList(any(), any());
    verify(sessionDataRepository, times(1)).saveAll(any());
  }

  @Test
  public void saveSessionDataForSessionId_Should_RefuseTheSessionOfAMembersOnlyChat() {
    SessionDataDTO sessionData = easyRandom.nextObject(SessionDataDTO.class);
    Session session = easyRandom.nextObject(Session.class);
    session.getUser().setUserId("asker");
    session.setTenantId(42L);
    when(sessionService.assertAskerHasAccess(any(), any())).thenReturn(session);
    org.mockito.Mockito.doThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException("no"))
        .when(membersOnlyChatAccess)
        .requireOrdinary(org.mockito.ArgumentMatchers.eq(session), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDataService.saveSessionData(SESSION_ID, sessionData));

    org.mockito.Mockito.verifyNoInteractions(sessionDataRepository);
  }

  @Test
  public void saveSessionData_Should_SaveValidatedSessionData() {
    SessionDataDTO sessionData = easyRandom.nextObject(SessionDataDTO.class);
    Session session = easyRandom.nextObject(Session.class);
    session.getUser().setUserId("asker");
    session.setTenantId(42L);
    when(sessionService.assertAskerHasAccess(any(), any())).thenReturn(session);

    sessionDataService.saveSessionData(SESSION_ID, sessionData);

    verify(sessionDataProvider, times(1)).createSessionDataList(any(), any());
    verify(sessionDataRepository, times(1)).saveAll(any());
  }

  @Test
  void generatedChoiceWorksWhenCustomNamesAreDisabled() {
    var session = aliasSession();
    when(tenantService.getRestrictedTenantDataFresh(42L)).thenReturn(tenant(false));
    sessionDataService.saveSessionData(
        session, new SessionDataDTO().displayName("katze_mika_1234"));
    verify(sessionDataRepository).saveAll(any());
  }

  @Test
  void sameLegacyAliasSurvivesAfterCustomNamesAreDisabledWithoutLookingUpPolicy() {
    var session = aliasSession();
    session.setSessionData(
        java.util.List.of(
            new de.caritas.cob.userservice.api.model.SessionData(
                session,
                de.caritas.cob.userservice.api.model.SessionData.SessionDataType.REGISTRATION,
                "displayName",
                "An existing legacy alias")));
    sessionDataService.saveSessionData(
        session, new SessionDataDTO().displayName("An existing legacy alias"));
    org.mockito.Mockito.verifyNoInteractions(tenantService);
    verify(sessionDataRepository).saveAll(any());
  }

  @Test
  void unrelatedSavePreservesAliasWithoutPolicyLookup() {
    sessionDataService.saveSessionData(aliasSession(), new SessionDataDTO().age("21"));
    org.mockito.Mockito.verifyNoInteractions(tenantService);
    verify(sessionDataRepository).saveAll(any());
  }

  @Test
  void anotherSessionOwnerCannotChangeAnAlias() {
    var session = aliasSession();
    session.getUser().setUserId("someone-else");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionDataService.saveSessionData(
                    session, new SessionDataDTO().displayName("katze_mika_1234")))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    org.mockito.Mockito.verifyNoInteractions(sessionDataRepository, tenantService);
  }

  @Test
  void policyOutageDoesNotPersistACustomOrGeneratedAlias() {
    when(tenantService.getRestrictedTenantDataFresh(42L))
        .thenThrow(new IllegalStateException("unavailable"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionDataService.saveSessionData(
                    aliasSession(), new SessionDataDTO().displayName("katze_mika_1234")))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
    org.mockito.Mockito.verifyNoInteractions(sessionDataRepository);
  }

  @Test
  void singleTenantLegacyOwnerMayChangeAliasUsingConfiguredPolicy() {
    var session = aliasSession();
    session.setTenantId(null);
    session.getUser().setTenantId(null);
    when(tenantService.getSingleTenancyTenantDataFresh()).thenReturn(tenant(true));
    sessionDataService.saveSessionData(session, new SessionDataDTO().displayName("A chosen alias"));
    verify(sessionDataRepository).saveAll(any());
    verify(tenantService).getSingleTenancyTenantDataFresh();
    org.mockito.Mockito.verify(tenantService, org.mockito.Mockito.never())
        .getRestrictedTenantDataFresh(any());
  }

  private Session aliasSession() {
    var session = new Session();
    var user = new de.caritas.cob.userservice.api.model.User();
    user.setUserId("asker");
    user.setTenantId(42L);
    session.setUser(user);
    session.setTenantId(42L);
    return session;
  }

  private de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO tenant(
      boolean custom) {
    return new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
        .id(42L)
        .settings(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                .featureDisplayNameEditable(custom));
  }
}
