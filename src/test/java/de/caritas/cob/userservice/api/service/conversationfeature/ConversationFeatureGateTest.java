package de.caritas.cob.userservice.api.service.conversationfeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class ConversationFeatureGateTest {

  private static final long TENANT_ID = 9L;
  private static final long AGENCY_ID = 31L;
  private static final long OTHER_AGENCY_ID = 32L;

  private final TenantService tenantService = mock(TenantService.class);
  private final EffectiveAgencySettingsLookup agencySettings =
      mock(EffectiveAgencySettingsLookup.class);
  private final ChatAgencyRepository chatAgencies = mock(ChatAgencyRepository.class);
  private final ConversationFeatureGate gate =
      new ConversationFeatureGate(tenantService, agencySettings, chatAgencies);

  @BeforeEach
  void everythingUnset() {
    traeger(settings -> {});
    agency(settings -> {});
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @ParameterizedTest
  @EnumSource(ConversationKind.class)
  void mediaUploadFollowsTheSwitchOfEachKindOfConversation(ConversationKind kind) {
    traeger(
        settings ->
            settings
                .featureMediaUploadAnonymousChatsEnabled(kind != ConversationKind.ANONYMOUS)
                .featureMediaUploadOneOnOneChatsEnabled(kind != ConversationKind.ONE_ON_ONE)
                .featureMediaUploadGroupChatsEnabled(kind != ConversationKind.GROUP));

    assertRefused(ConversationFeature.MEDIA_UPLOAD, kind);
    for (var other : ConversationKind.values()) {
      if (other != kind) {
        assertAllowed(ConversationFeature.MEDIA_UPLOAD, other);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ConversationKind.class)
  void voiceMessagesFollowTheSwitchOfEachKindOfConversation(ConversationKind kind) {
    traeger(
        settings ->
            settings
                .featureVoiceMessagesAnonymousChatsEnabled(kind != ConversationKind.ANONYMOUS)
                .featureVoiceMessagesOneOnOneChatsEnabled(kind != ConversationKind.ONE_ON_ONE)
                .featureVoiceMessagesGroupChatsEnabled(kind != ConversationKind.GROUP));

    assertRefused(ConversationFeature.VOICE_MESSAGES, kind);
    for (var other : ConversationKind.values()) {
      if (other != kind) {
        assertAllowed(ConversationFeature.VOICE_MESSAGES, other);
      }
    }
  }

  @Test
  void voiceMessagesOfTheBeratungsstelleAreRead() {
    agency(settings -> settings.featureVoiceMessagesEnabled(false));
    assertRefused(ConversationFeature.VOICE_MESSAGES, ConversationKind.ONE_ON_ONE);

    agency(settings -> settings.featureVoiceMessagesGroupChatsEnabled(false));
    assertRefused(ConversationFeature.VOICE_MESSAGES, ConversationKind.GROUP);
    assertAllowed(ConversationFeature.VOICE_MESSAGES, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void severalFeaturesMustAllBeOn_EitherOffRefuses() {
    traeger(settings -> settings.featureVoiceMessagesEnabled(false));
    assertThatThrownBy(
            () ->
                gate.require(
                    List.of(ConversationFeature.MEDIA_UPLOAD, ConversationFeature.VOICE_MESSAGES),
                    session(ConversationType.AGENCY_COUNSELLING)))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    traeger(settings -> settings.featureMediaUploadEnabled(false));
    assertThatThrownBy(
            () ->
                gate.require(
                    List.of(ConversationFeature.MEDIA_UPLOAD, ConversationFeature.VOICE_MESSAGES),
                    session(ConversationType.AGENCY_COUNSELLING)))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    traeger(settings -> {});
    assertThatCode(
            () ->
                gate.require(
                    List.of(ConversationFeature.MEDIA_UPLOAD, ConversationFeature.VOICE_MESSAGES),
                    session(ConversationType.AGENCY_COUNSELLING)))
        .doesNotThrowAnyException();
  }

  @Test
  void severalFeaturesAreReadWithOneQueryPerProvider() {
    gate.require(
        List.of(ConversationFeature.MEDIA_UPLOAD, ConversationFeature.VOICE_MESSAGES),
        session(ConversationType.AGENCY_COUNSELLING));

    verify(tenantService).getRestrictedTenantDataFresh(TENANT_ID);
    verify(agencySettings).findEffectiveSettings(AGENCY_ID, TENANT_ID);
  }

  @Test
  void supervisionKnowsNoSwitchPerKindForGroups_OnlyTheMaster() {
    traeger(settings -> settings.featureSupervisionEnabled(true));
    assertAllowed(ConversationFeature.SUPERVISION, ConversationKind.GROUP);

    traeger(settings -> settings.featureSupervisionEnabled(false));
    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.GROUP);
  }

  @Test
  void aSessionIsOfTheKindTheAppShows() {
    var anonymousByPostcode = session(null);
    anonymousByPostcode.setPostcode("00000");
    var anonymousByName = session(null);
    var user = new de.caritas.cob.userservice.api.model.User();
    user.setUsername("Anonymous-1234");
    anonymousByName.setUser(user);
    var anonymousByRegistration = session(null);
    anonymousByRegistration.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    var storedAsCounselling = session(ConversationType.AGENCY_COUNSELLING);
    storedAsCounselling.setPostcode("00000");

    assertThat(ConversationKind.of(anonymousByPostcode)).isEqualTo(ConversationKind.ANONYMOUS);
    assertThat(ConversationKind.of(anonymousByName)).isEqualTo(ConversationKind.ANONYMOUS);
    assertThat(ConversationKind.of(anonymousByRegistration)).isEqualTo(ConversationKind.ANONYMOUS);
    assertThat(ConversationKind.of(session(ConversationType.LIVE_CHAT)))
        .isEqualTo(ConversationKind.ANONYMOUS);
    // A stored kind wins over the old signals, as in the app.
    assertThat(ConversationKind.of(storedAsCounselling)).isEqualTo(ConversationKind.ONE_ON_ONE);
    assertThat(ConversationKind.of(session(null))).isEqualTo(ConversationKind.ONE_ON_ONE);
    assertThat(ConversationKind.of(session(ConversationType.INTERNAL_GROUP)))
        .isEqualTo(ConversationKind.GROUP);
    assertThat(ConversationKind.of(session(ConversationType.SELF_HELP)))
        .isEqualTo(ConversationKind.GROUP);
  }

  @Test
  void aSessionWithoutATraegerIsJudgedByTheSingleTraegerInstallation() {
    var session = session(ConversationType.AGENCY_COUNSELLING);
    session.setTenantId(null);
    session.setAgencyId(null);
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(
            new RestrictedTenantDTO()
                .id(1L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureMediaUploadEnabled(false)));

    assertThatThrownBy(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, session))
        .isInstanceOf(CustomValidationHttpStatusException.class);
    verify(tenantService, never()).getRestrictedTenantDataFresh(anyLong());
  }

  @Test
  void aSwitchNobodySetIsOn() {
    assertAllowed(ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE);
    assertAllowed(ConversationFeature.MEDIA_UPLOAD, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void theMasterOfTheBeratungsstelleOffRefusesEvenWhenTheKindIsOn() {
    agency(
        settings ->
            settings.featureMediaUploadEnabled(false).featureMediaUploadGroupChatsEnabled(true));

    assertRefused(ConversationFeature.MEDIA_UPLOAD, ConversationKind.GROUP);
  }

  @Test
  void withoutABeratungsstelleOnlyTheTraegerDecides() {
    gate.require(ConversationFeature.MEDIA_UPLOAD, ConversationKind.GROUP, TENANT_ID, null);

    verifyNoInteractions(agencySettings);
  }

  @Test
  void settingsTheTraegerDoesNotServeAreNeverTakenAsOn() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID));
    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE);

    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID)).thenReturn(null);
    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void settingsOfAnotherTraegerAreNeverTakenAsOn() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID + 1)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()));

    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void aBeratungsstelleTheAgencyServiceDoesNotKnowIsNeverTakenAsOn() {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID)).thenReturn(Optional.empty());

    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void anUnreachableTenantServiceIsAnOutage_NotAYes() {
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(new ResourceAccessException("down"));

    assertThatThrownBy(
            () ->
                gate.require(
                    ConversationFeature.SUPERVISION,
                    ConversationKind.ONE_ON_ONE,
                    TENANT_ID,
                    AGENCY_ID))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anUnreachableAgencyServiceIsAnOutage_NotAYes() {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ResourceAccessException("down"));

    assertThatThrownBy(
            () ->
                gate.require(
                    ConversationFeature.SUPERVISION,
                    ConversationKind.ONE_ON_ONE,
                    TENANT_ID,
                    AGENCY_ID))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void theSettingsAreReadInTheTraegerOfTheConversation_NotInTheCallersTraeger() {
    var tenantDuringReads = new AtomicReference<Long>();
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenAnswer(
            invocation -> {
              tenantDuringReads.set(TenantContext.getCurrentTenant());
              return new RestrictedTenantDTO()
                  .id(TENANT_ID)
                  .settings(
                      new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings());
            });
    TenantContext.setCurrentTenant(1234L);

    gate.require(ConversationFeature.MEDIA_UPLOAD, ConversationKind.GROUP, TENANT_ID, null);

    assertThat(tenantDuringReads.get()).isEqualTo(TENANT_ID);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(1234L);
  }

  @Test
  void aSingleTraegerInstallationAsksTheSingleTenancyTraeger() {
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(
            new RestrictedTenantDTO()
                .id(1L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureSupervisionEnabled(false)));

    assertThatThrownBy(
            () ->
                gate.require(
                    ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE, null, null))
        .isInstanceOf(CustomValidationHttpStatusException.class);
    verify(tenantService, never()).getRestrictedTenantDataFresh(anyLong());
  }

  @Test
  void aSessionIsJudgedByItsKindTenantAndBeratungsstelle() {
    var session = new Session();
    session.setConversationType(ConversationType.LIVE_CHAT);
    session.setTenantId(TENANT_ID);
    session.setAgencyId(AGENCY_ID);
    traeger(settings -> settings.featureMediaUploadAnonymousChatsEnabled(false));

    assertThatThrownBy(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, session))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @Test
  void aGroupChatWithOneBeratungsstelleIsJudgedByIt() {
    var chat = chatOfTenant(77L);
    var link = mock(ChatAgency.class);
    when(link.getAgencyId()).thenReturn(AGENCY_ID);
    when(chatAgencies.findByChat_Id(77L)).thenReturn(List.of(link));
    agency(settings -> settings.featureMediaUploadGroupChatsEnabled(false));

    assertThatThrownBy(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @Test
  void aGroupChatOfSeveralBeratungsstellenIsRefusedWhenAnyOfThemSaysNo() {
    var chat = chatOfTenant(78L);
    var first = linkTo(AGENCY_ID);
    var second = linkTo(OTHER_AGENCY_ID);
    when(chatAgencies.findByChat_Id(78L)).thenReturn(List.of(first, second));
    agency(AGENCY_ID, settings -> {});
    agency(OTHER_AGENCY_ID, settings -> settings.featureMediaUploadEnabled(false));

    assertThatThrownBy(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @Test
  void aGroupChatOfSeveralBeratungsstellenIsAllowedWhenAllOfThemSayYes() {
    var chat = chatOfTenant(78L);
    var links = List.of(linkTo(AGENCY_ID), linkTo(OTHER_AGENCY_ID));
    when(chatAgencies.findByChat_Id(78L)).thenReturn(links);
    agency(AGENCY_ID, settings -> settings.featureMediaUploadGroupChatsEnabled(true));
    agency(OTHER_AGENCY_ID, settings -> {});

    assertThatCode(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .doesNotThrowAnyException();
    verify(agencySettings).findEffectiveSettings(AGENCY_ID, TENANT_ID);
    verify(agencySettings).findEffectiveSettings(OTHER_AGENCY_ID, TENANT_ID);
  }

  @Test
  void aGroupChatOfSeveralBeratungsstellenIsRefusedWhenOneIsUnknown() {
    var chat = chatOfTenant(78L);
    var links = List.of(linkTo(AGENCY_ID), linkTo(OTHER_AGENCY_ID));
    when(chatAgencies.findByChat_Id(78L)).thenReturn(links);
    when(agencySettings.findEffectiveSettings(OTHER_AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @Test
  void aGroupChatWithoutAnyBeratungsstelleIsJudgedByTheTraegerAlone() {
    var chat = chatOfTenant(79L);
    when(chatAgencies.findByChat_Id(79L)).thenReturn(List.of());

    assertThatCode(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .doesNotThrowAnyException();
    verifyNoInteractions(agencySettings);
  }

  // ---- the Traeger is asked first

  @Test
  void aTraegerThatSaysNo_Should_NotAskTheAgencyService() {
    traeger(settings -> settings.featureMediaUploadEnabled(false));

    assertRefused(ConversationFeature.MEDIA_UPLOAD, ConversationKind.ONE_ON_ONE);

    verifyNoInteractions(agencySettings);
  }

  @Test
  void aTraegerThatSaysNo_Should_AnswerForbidden_EvenWhileTheAgencyServiceIsDown() {
    traeger(settings -> settings.featureMediaUploadEnabled(false));
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ResourceAccessException("down"));

    assertRefused(ConversationFeature.MEDIA_UPLOAD, ConversationKind.ONE_ON_ONE);
  }

  @Test
  void aTraegerThatSaysYes_Should_StillBeAnOutage_WhenTheAgencyServiceIsDown() {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ResourceAccessException("down"));

    assertThatThrownBy(
            () ->
                gate.require(
                    ConversationFeature.MEDIA_UPLOAD,
                    ConversationKind.ONE_ON_ONE,
                    TENANT_ID,
                    AGENCY_ID))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  // ---- what a provider answer means

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 422})
  void aTenantServiceThatAnswersWithAClientError_Should_BeARefusal_NotAnOutage(int status) {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenThrow(clientError(status, "{\"secret\":\"downstream body\"}"));

    assertRefusedAsDisabled();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 422})
  void anAgencyServiceThatAnswersWithAClientError_Should_BeARefusal_NotAnOutage(int status) {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(clientError(status, "{\"secret\":\"downstream body\"}"));

    assertRefusedAsDisabled();
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429})
  void aTimeoutOrRateLimitAnswer_Should_BeAnOutage(int status) {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenThrow(clientError(status, "body"));

    assertThatThrownBy(this::requireOneOnOne).isInstanceOf(ServiceUnavailableException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void aServerError_Should_BeAnOutage(int status) {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.valueOf(status),
                "x",
                new org.springframework.http.HttpHeaders(),
                "body".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(this::requireOneOnOne).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aForbiddenFromTheAgencyLookup_Should_BeA403WithTheReasonTheAppCanShow() {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ForbiddenException("AgencyService refused access to the requested agency"));

    assertRefusedAsDisabled();
  }

  @Test
  void anUnreadableAgencyAnswer_Should_BeAnOutage() {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ServiceUnavailableException("Agency policy projection is unreadable"));

    assertThatThrownBy(this::requireOneOnOne).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aDownstreamBodyNeverReachesTheLogOrTheApiError() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenThrow(clientError(400, "{\"secret\":\"downstream body\"}"))
        .thenThrow(clientError(429, "{\"secret\":\"downstream body\"}"));

    try (var logs = LogbackCaptor.forClass(ConversationFeatureGate.class)) {
      var refusal = catchThrowable(this::requireOneOnOne);
      var outage = catchThrowable(this::requireOneOnOne);

      for (var thrown : List.of(refusal, outage)) {
        assertThat(thrown).hasNoCause();
        assertThat(String.valueOf(thrown.getMessage())).doesNotContain("downstream body");
      }
      assertThat(logs.events())
          .allSatisfy(
              event -> assertThat(event.getFormattedMessage()).doesNotContain("downstream body"));
      assertThat(logs.contains(Level.WARN, "400")).isTrue();
    }
  }

  @Test
  void theRefusalIsA403WithTheReasonTheAppCanShow() {
    traeger(settings -> settings.featureSupervisionEnabled(false));

    assertThatThrownBy(
            () ->
                gate.require(
                    ConversationFeature.SUPERVISION, ConversationKind.ONE_ON_ONE, TENANT_ID, null))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refusal -> {
              assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(refusal.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("FEATURE_DISABLED");
            });
  }

  // ---- helpers

  private void assertAllowed(ConversationFeature feature, ConversationKind kind) {
    assertThatCode(() -> gate.require(feature, kind, TENANT_ID, AGENCY_ID))
        .as("%s in %s", feature, kind)
        .doesNotThrowAnyException();
  }

  private void assertRefused(ConversationFeature feature, ConversationKind kind) {
    assertThatThrownBy(() -> gate.require(feature, kind, TENANT_ID, AGENCY_ID))
        .as("%s in %s", feature, kind)
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  private Chat chatOfTenant(long chatId) {
    var chat = mock(Chat.class);
    var owner = mock(Consultant.class);
    when(owner.getTenantId()).thenReturn(TENANT_ID);
    when(chat.getId()).thenReturn(chatId);
    when(chat.getChatOwner()).thenReturn(owner);
    return chat;
  }

  private void traeger(
      Consumer<de.caritas.cob.userservice.tenantservice.generated.web.model.Settings> change) {
    var settings = new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings();
    change.accept(settings);
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID).settings(settings));
  }

  private void agency(Consumer<Settings> change) {
    agency(AGENCY_ID, change);
  }

  private void agency(long agencyId, Consumer<Settings> change) {
    var settings = new Settings();
    change.accept(settings);
    when(agencySettings.findEffectiveSettings(agencyId, TENANT_ID))
        .thenReturn(Optional.of(settings));
  }

  private static ChatAgency linkTo(long agencyId) {
    var link = mock(ChatAgency.class);
    when(link.getAgencyId()).thenReturn(agencyId);
    return link;
  }

  private static Session session(ConversationType type) {
    var session = new Session();
    session.setConversationType(type);
    session.setTenantId(TENANT_ID);
    session.setAgencyId(AGENCY_ID);
    return session;
  }

  private static HttpClientErrorException clientError(int status, String body) {
    return HttpClientErrorException.create(
        HttpStatus.valueOf(status),
        "x",
        new org.springframework.http.HttpHeaders(),
        body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  private void requireOneOnOne() {
    gate.require(
        ConversationFeature.MEDIA_UPLOAD, ConversationKind.ONE_ON_ONE, TENANT_ID, AGENCY_ID);
  }

  private void assertRefusedAsDisabled() {
    assertThatThrownBy(this::requireOneOnOne)
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refusal -> {
              assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(refusal.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("FEATURE_DISABLED");
              assertThat(refusal).hasNoCause();
            });
  }
}
