package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AskerEmailCollectionPolicyTest {
  @Mock TenantService tenants;
  @Mock SessionRepository sessions;
  @Mock UserChatRepository userChats;
  @Mock PlatformTransactionManager transactions;
  @Mock TransactionStatus transaction;
  private AskerEmailCollectionPolicy policy;
  private final User user = new User("asker", null, "login", "stored@example.org", false);
  private Settings settings;

  @BeforeEach
  void setup() {
    user.setTenantId(42L);
    settings =
        new Settings()
            .featureAskerEmailEnabled(true)
            .featureAskerEmailCollectionAgencyCounsellingEnabled(true)
            .featureAskerEmailCollectionLiveChatEnabled(true)
            .featureAskerEmailCollectionSelfHelpEnabled(true);
    lenient()
        .when(tenants.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    lenient().when(transactions.getTransaction(any())).thenReturn(transaction);
    policy = new AskerEmailCollectionPolicy(tenants, sessions, userChats, transactions);
  }

  @Test
  void unrestrictedAndLegacyMissingCollectionFlagsKeepMasterOnlyCompatibility() {
    assertThat(policy.allowedForAccount(user)).isTrue();
    settings
        .featureAskerEmailCollectionAgencyCounsellingEnabled(null)
        .featureAskerEmailCollectionLiveChatEnabled(null)
        .featureAskerEmailCollectionSelfHelpEnabled(null);
    assertThat(policy.allowedForAccount(user)).isTrue();
    verifyNoInteractions(sessions, userChats, transactions);
  }

  @Test
  void masterOffCapsEveryTypeWithoutInventingAnOwnershipFallback() {
    settings.featureAskerEmailEnabled(false);
    assertThat(policy.allowedForAccount(user)).isFalse();
    assertThatThrownBy(() -> policy.requireForAccount(user)).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(sessions, userChats, transactions);
  }

  @ParameterizedTest
  @EnumSource(ConversationType.class)
  void registrationUsesOnlyItsVerifiedContext(ConversationType type) {
    allowOnly(ConversationType.SELF_HELP);
    if (type == ConversationType.SELF_HELP) policy.requireForRegistration(42L, type);
    else
      assertThatThrownBy(() -> policy.requireForRegistration(42L, type))
          .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(sessions, userChats, transactions);
  }

  @ParameterizedTest
  @EnumSource(ConversationType.class)
  void onlyPersonalSessionModalitiesAuthorizeThroughOwnedSessionRows(ConversationType type) {
    allowOnly(type);
    when(sessions.findByUser(user)).thenReturn(List.of(session(type, user)));
    assertThat(policy.allowedForAccount(user))
        .isEqualTo(
            type == ConversationType.AGENCY_COUNSELLING || type == ConversationType.LIVE_CHAT);
  }

  @Test
  void genuineJoinedSelfHelpMembershipAuthorizesTheSelfHelpContext() {
    allowOnly(ConversationType.SELF_HELP);
    when(userChats.findByUser(user))
        .thenReturn(List.of(membership(ConversationType.SELF_HELP, user)));
    assertThat(policy.allowedForAccount(user)).isTrue();
  }

  @Test
  void discoverableOrAnotherPersonsGroupIsNotAMembershipProof() {
    allowOnly(ConversationType.SELF_HELP);
    var other = new User("other", null, "other-login", "other@example.org", false);
    when(userChats.findByUser(user))
        .thenReturn(
            List.of(
                membership(ConversationType.SELF_HELP, other),
                membership(ConversationType.INTERNAL_GROUP, user)));
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void wrongUserSessionsCannotAuthorizeTheAccount() {
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    var other = new User("other", null, "other-login", "other@example.org", false);
    when(sessions.findByUser(user))
        .thenReturn(List.of(session(ConversationType.AGENCY_COUNSELLING, other)));
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void aRealMixedAccountUsesTheAllowedOwnedContextButLiveOnlyDoesNot() {
    settings.featureAskerEmailCollectionLiveChatEnabled(false);
    when(sessions.findByUser(user))
        .thenReturn(
            List.of(session(ConversationType.LIVE_CHAT, user)),
            List.of(
                session(ConversationType.LIVE_CHAT, user),
                session(ConversationType.AGENCY_COUNSELLING, user)));
    assertThat(policy.allowedForAccount(user)).isFalse();
    assertThat(policy.allowedForAccount(user)).isTrue();
  }

  @Test
  void legacyRegistrationFallbackDoesNotUseTeamAgencyAsAModality() {
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    var legacy = session(null, user);
    legacy.setTeamSession(true);
    when(sessions.findByUser(user)).thenReturn(List.of(legacy));
    assertThat(policy.allowedForAccount(user)).isTrue();
  }

  @Test
  void noOrUnknownOwnedContextDoesNotDefaultToAgencyCounselling() {
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    assertThat(policy.allowedForAccount(user)).isFalse();
    var unknown = session(null, user);
    // Simulate an unreadable legacy discriminator without invoking a non-null setter.
    org.springframework.test.util.ReflectionTestUtils.setField(unknown, "registrationType", null);
    when(sessions.findByUser(user)).thenReturn(List.of(unknown));
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void deliverySwitchesNeverSubstituteForCollectionPermission() {
    settings.featureAskerEmailLiveChatEnabled(false);
    policy.requireForRegistration(42L, ConversationType.LIVE_CHAT);
    settings
        .featureAskerEmailLiveChatEnabled(true)
        .featureAskerEmailCollectionLiveChatEnabled(false);
    assertThatThrownBy(() -> policy.requireForRegistration(42L, ConversationType.LIVE_CHAT))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void policyAndOwnedAvailabilityAreFreshAtTheNextAction() {
    settings.featureAskerEmailCollectionLiveChatEnabled(false);
    when(sessions.findByUser(user))
        .thenReturn(List.of(session(ConversationType.AGENCY_COUNSELLING, user)), List.of());
    assertThat(policy.allowedForAccount(user)).isTrue();
    assertThat(policy.allowedForAccount(user)).isFalse();
    verify(tenants, times(2)).getRestrictedTenantDataFresh(42L);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(longs = {99L, 0L, -1L})
  void explicitForeignOrInvalidPersonalSessionTenantDoesNotGrantAContext(long sessionTenant) {
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    var foreign = session(ConversationType.AGENCY_COUNSELLING, user);
    foreign.setTenantId(sessionTenant);
    when(sessions.findByUser(user)).thenReturn(List.of(foreign));
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void configuredSingleTenantCorroboratesAnOwnedSessionWhenLegacyAccountTenantIsNull() {
    user.setTenantId(null);
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    when(tenants.getSingleTenancyTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    when(sessions.findByUser(user))
        .thenReturn(List.of(session(ConversationType.AGENCY_COUNSELLING, user)));
    assertThat(policy.allowedForAccount(user)).isTrue();
    verify(tenants, times(1)).getSingleTenancyTenantDataFresh();
    verify(tenants, never()).getRestrictedTenantDataFresh(any());
  }

  @Test
  void configuredSingleTenantDoesNotAuthorizeAnExplicitForeignOwnedSession() {
    user.setTenantId(null);
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    when(tenants.getSingleTenancyTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
    var foreign = session(ConversationType.AGENCY_COUNSELLING, user);
    foreign.setTenantId(99L);
    when(sessions.findByUser(user)).thenReturn(List.of(foreign));
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void restrictiveUnionRequiresValidSingleProjectionOrConcreteMultitenantOwner() {
    user.setTenantId(null);
    allowOnly(ConversationType.AGENCY_COUNSELLING);
    when(tenants.getSingleTenancyTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(0L).settings(settings));
    assertThatThrownBy(() -> policy.requireForAccount(user))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(policy.capabilityForAccount(user)).isFalse();
    verifyNoInteractions(sessions, userChats);
    when(tenants.isMultitenancyEnabled()).thenReturn(true);
    assertThatThrownBy(() -> policy.requireForAccount(user))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(tenants, times(2)).getSingleTenancyTenantDataFresh();
  }

  @Test
  void actualAccountTenantWinsOverTheRequestHeader() {
    TenantContext.setCurrentTenant(999L);
    try {
      assertThat(policy.allowedForAccount(user)).isTrue();
    } finally {
      TenantContext.clear();
    }
    verify(tenants).getRestrictedTenantDataFresh(42L);
    verify(tenants, never()).getRestrictedTenantDataFresh(999L);
  }

  @Test
  void unavailableOrForeignPolicyFailsWritesClosedButDoesNotBreakCapabilityRead() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenThrow(new IllegalStateException("offline"));
    assertThatThrownBy(() -> policy.requireForAccount(user))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(policy.capabilityForAccount(user)).isFalse();
    doReturn(new RestrictedTenantDTO().id(99L).settings(settings))
        .when(tenants)
        .getRestrictedTenantDataFresh(42L);
    assertThat(policy.capabilityForAccount(user)).isFalse();
    verifyNoInteractions(sessions, userChats);
  }

  @Test
  void contextDatabaseFailureIsUnavailableWhileProgrammingErrorsRemainVisible() {
    allowOnly(ConversationType.SELF_HELP);
    when(sessions.findByUser(user))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "private database body"));
    assertThatThrownBy(() -> policy.requireForAccount(user))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    assertThat(policy.capabilityForAccount(user)).isFalse();
    doThrow(new IllegalArgumentException("programming failure")).when(sessions).findByUser(user);
    assertThatThrownBy(() -> policy.capabilityForAccount(user))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void contextFailuresHaveClassOnlyDiagnosticsWithoutPrivateData(boolean databaseFailure) {
    allowOnly(ConversationType.SELF_HELP);
    RuntimeException cause = new IllegalStateException("private-cause select confidential_table");
    RuntimeException failure =
        databaseFailure
            ? new org.springframework.dao.DataAccessResourceFailureException(
                "private-db-body private@example.test", cause)
            : new org.springframework.transaction.CannotCreateTransactionException(
                "private-transaction-body private@example.test", cause);
    if (databaseFailure) when(sessions.findByUser(user)).thenThrow(failure);
    else when(transactions.getTransaction(any())).thenThrow(failure);
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(AskerEmailCollectionPolicy.class);
    var previousLevel = logger.getLevel();
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logger.setLevel(ch.qos.logback.classic.Level.WARN);
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(() -> policy.requireForAccount(user))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage("Current asker collection contexts are unavailable")
          .hasNoCause();
      assertThat(policy.capabilityForAccount(user)).isFalse();
      assertThat(appender.list)
          .hasSize(2)
          .allSatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage())
                    .isEqualTo(
                        "Asker collection contexts unavailable: "
                            + failure.getClass().getSimpleName());
                assertThat(event.getFormattedMessage())
                    .doesNotContain(
                        "private-db-body",
                        "private-transaction-body",
                        "private@example.test",
                        "private-cause",
                        "select confidential_table");
                assertThat(event.getArgumentArray())
                    .containsExactly(failure.getClass().getSimpleName());
                assertThat(event.getThrowableProxy()).isNull();
              });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(previousLevel);
    }
  }

  private void allowOnly(ConversationType type) {
    settings
        .featureAskerEmailCollectionAgencyCounsellingEnabled(
            type == ConversationType.AGENCY_COUNSELLING)
        .featureAskerEmailCollectionLiveChatEnabled(type == ConversationType.LIVE_CHAT)
        .featureAskerEmailCollectionSelfHelpEnabled(type == ConversationType.SELF_HELP);
  }

  private Session session(ConversationType type, User owner) {
    return Session.builder()
        .id(11L)
        .user(owner)
        .tenantId(42L)
        .postcode("00000")
        .registrationType(Session.RegistrationType.REGISTERED)
        .status(Session.SessionStatus.DONE)
        .conversationType(type)
        .build();
  }

  private UserChat membership(ConversationType type, User owner) {
    var chat = new Chat();
    chat.setId(77L);
    chat.setConversationType(type);
    return UserChat.builder().user(owner).chat(chat).build();
  }
}
