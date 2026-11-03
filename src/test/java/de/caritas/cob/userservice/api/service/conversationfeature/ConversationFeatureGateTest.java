package de.caritas.cob.userservice.api.service.conversationfeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class ConversationFeatureGateTest {

  private static final long TENANT_ID = 9L;
  private static final long AGENCY_ID = 31L;

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
                .featureMediaUploadGroupChatsEnabled(kind != ConversationKind.GROUP)
                .featureMediaUploadSupervisionChatsEnabled(kind != ConversationKind.SUPERVISION));

    assertRefused(ConversationFeature.MEDIA_UPLOAD, kind);
    for (var other : ConversationKind.values()) {
      if (other != kind) {
        assertAllowed(ConversationFeature.MEDIA_UPLOAD, other);
      }
    }
  }

  @Test
  void supervisionKnowsNoSwitchForGroupsAndSupervisionRooms_OnlyTheMaster() {
    traeger(settings -> settings.featureSupervisionEnabled(true));
    assertAllowed(ConversationFeature.SUPERVISION, ConversationKind.GROUP);
    assertAllowed(ConversationFeature.SUPERVISION, ConversationKind.SUPERVISION);

    traeger(settings -> settings.featureSupervisionEnabled(false));
    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.GROUP);
    assertRefused(ConversationFeature.SUPERVISION, ConversationKind.SUPERVISION);
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
  void aGroupChatWithSeveralBeratungsstellenIsJudgedByTheTraegerAlone() {
    var chat = chatOfTenant(78L);
    var first = mock(ChatAgency.class);
    var second = mock(ChatAgency.class);
    when(chatAgencies.findByChat_Id(78L)).thenReturn(List.of(first, second));
    agency(settings -> settings.featureMediaUploadEnabled(false));

    assertThatCode(() -> gate.require(ConversationFeature.MEDIA_UPLOAD, chat))
        .doesNotThrowAnyException();
    verifyNoInteractions(agencySettings);
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
    var settings = new Settings();
    change.accept(settings);
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(settings));
  }
}
