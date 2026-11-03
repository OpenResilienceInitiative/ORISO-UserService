package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskerEmailCollectionGuardTest {
  @Mock UserRepository users;
  @Mock AuthenticatedUser authenticatedUser;
  @Mock TenantService tenants;
  @Mock IdentitySecondFactor secondFactor;
  @Mock de.caritas.cob.userservice.api.port.out.SessionRepository sessions;
  @Mock de.caritas.cob.userservice.api.port.out.UserChatRepository userChats;
  @Mock org.springframework.transaction.PlatformTransactionManager transactions;
  @Mock org.springframework.transaction.TransactionStatus transaction;
  AskerEmailCollectionGuard guard;

  @BeforeEach
  void actualAsker() {
    lenient().when(transactions.getTransaction(any())).thenReturn(transaction);
    guard =
        new AskerEmailCollectionGuard(
            users,
            authenticatedUser,
            new AskerEmailCollectionPolicy(tenants, sessions, userChats, transactions),
            secondFactor);
    var user = new User();
    user.setUserId("asker");
    user.setTenantId(42L);
    user.setEmail("existing@example.org");
    org.mockito.Mockito.lenient().when(authenticatedUser.getUserId()).thenReturn("asker");
    org.mockito.Mockito.lenient()
        .when(users.findByUserIdAndDeleteDateIsNull("asker"))
        .thenReturn(Optional.of(user));
  }

  @Test
  void anonymousLiveChatCannotSendOtpEvenForAPreviouslyStoredAddress() {
    when(authenticatedUser.isAnonymous()).thenReturn(true);

    assertThatThrownBy(() -> guard.beforeStart("existing@example.org"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(secondFactor);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "featureAskerEmailCollectionAgencyCounsellingEnabled",
        "featureAskerEmailCollectionSelfHelpEnabled"
      })
  void restrictiveCollectionCannotAuthorizeAnUnknownAccountContext(String restrictedKey)
      throws Exception {
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var settings =
        mapper.convertValue(
            java.util.Map.of("featureAskerEmailEnabled", true, restrictedKey, false),
            Settings.class);
    when(tenants.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));

    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(secondFactor);
  }

  @Test
  void disabledCollectionRefusesNewTargetBeforeMail() {
    disabled();
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(secondFactor);
  }

  @Test
  void existingSameAddressDoesNotCollectAnything() {
    guard.beforeStart("EXISTING@example.org");
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void disablingCollectionDuringEnrollmentStopsPendingDifferentAddress() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker")).thenReturn(List.of("new@example.org"));
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void existingSameAddressMayFinishWithoutCollectingANewAddress() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker"))
        .thenReturn(List.of("EXISTING@example.org"));
    guard.beforeFinish();
  }

  @Test
  void existingPendingAddressCanFinishDuringPolicyOutageButANewOneCannot() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenThrow(new IllegalStateException("offline"));
    when(secondFactor.emailVerificationAddresses("asker"))
        .thenReturn(List.of("  EXISTING@example.org  "), List.of("new@example.org"));
    guard.beforeFinish();
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void losingTheOnlyAllowedContextBeforeFinishRefusesThePendingNewAddress() {
    var settings = new Settings().featureAskerEmailEnabled(true);
    when(tenants.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    var user = users.findByUserIdAndDeleteDateIsNull("asker").orElseThrow();
    var owned =
        de.caritas.cob.userservice.api.model.Session.builder()
            .id(11L)
            .user(user)
            .registrationType(
                de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED)
            .conversationType(
                de.caritas.cob.userservice.api.model.ConversationType.AGENCY_COUNSELLING)
            .postcode("00000")
            .status(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW)
            .build();
    when(sessions.findByUser(user)).thenReturn(List.of(owned), List.of());
    guard.beforeStart("new@example.org");
    when(secondFactor.emailVerificationAddresses("asker")).thenReturn(List.of("new@example.org"));
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void unknownOrMixedPendingTargetsFailClosed() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker")).thenReturn(List.of());
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
    when(secondFactor.emailVerificationAddresses("asker"))
        .thenReturn(List.of("existing@example.org", "other@example.org"));
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void enabledCollectionDoesNotNeedProviderMetadata() {
    givenOwnedCounsellingSession(users.findByUserIdAndDeleteDateIsNull("asker").orElseThrow());
    when(tenants.getRestrictedTenantDataFresh(42L)).thenReturn(tenant(true));
    guard.beforeFinish();
    verifyNoInteractions(secondFactor);
  }

  @Test
  void policyOutageAndMismatchedTenantFailClosed() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenThrow(new IllegalStateException("offline"));
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    doReturn(tenant(true).id(99L)).when(tenants).getRestrictedTenantDataFresh(42L);
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    verifyNoInteractions(secondFactor);
  }

  @Test
  void aMissingAnonymousAskerRowCannotBypassCollectionChecks() {
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.empty());
    when(authenticatedUser.getRoles())
        .thenReturn(
            java.util.Set.of(
                de.caritas.cob.userservice.api.config.auth.UserRole.ANONYMOUS.getValue()));
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ServiceUnavailableException.class);
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void consultantWithoutAnAskerRowIsUnaffected() {
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.empty());
    guard.beforeStart("consultant@example.org");
    guard.beforeFinish();
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void legacySingleTenantOtpFinishUsesConfiguredPolicyBeforeProviderEffects() {
    var user = new User();
    user.setUserId("asker");
    org.mockito.Mockito.lenient()
        .when(users.findByUserIdAndDeleteDateIsNull("asker"))
        .thenReturn(Optional.of(user));
    givenOwnedCounsellingSession(user);
    when(tenants.getSingleTenancyTenantDataFresh()).thenReturn(tenant(true));
    guard.beforeFinish();
    verify(tenants).getSingleTenancyTenantDataFresh();
    verifyNoInteractions(secondFactor);
  }

  private void givenOwnedCounsellingSession(User user) {
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setUser(user);
    session.setConversationType(
        de.caritas.cob.userservice.api.model.ConversationType.AGENCY_COUNSELLING);
    when(sessions.findByUser(user)).thenReturn(List.of(session));
  }

  private void disabled() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenReturn(tenant(false));
  }

  private RestrictedTenantDTO tenant(boolean enabled) {
    return new RestrictedTenantDTO()
        .id(42L)
        .settings(new Settings().featureAskerEmailEnabled(enabled));
  }
}
