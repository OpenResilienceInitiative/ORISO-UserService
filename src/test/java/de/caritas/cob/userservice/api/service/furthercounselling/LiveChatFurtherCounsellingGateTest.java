package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** ORISO-Frontend#2251: live chats need the Beratungsstelle's switch; 1:1 stays as it was. */
@ExtendWith(MockitoExtension.class)
class LiveChatFurtherCounsellingGateTest {

  private static final long AGENCY_ID = 21L;
  private static final long TENANT_ID = 4L;

  @Mock EffectiveAgencySettingsLookup lookup;
  @InjectMocks LiveChatFurtherCounsellingGate gate;

  private Session liveChat;

  @BeforeEach
  void setUp() {
    liveChat = new Session();
    liveChat.setId(7L);
    liveChat.setAgencyId(AGENCY_ID);
    liveChat.setTenantId(TENANT_ID);
    liveChat.setConversationType(ConversationType.LIVE_CHAT);
    liveChat.setRegistrationType(RegistrationType.ANONYMOUS);
  }

  @Test
  void registeredOneToOneConversationNeedsNoSwitch() {
    var oneToOne = new Session();
    oneToOne.setAgencyId(AGENCY_ID);
    oneToOne.setConversationType(ConversationType.AGENCY_COUNSELLING);
    oneToOne.setRegistrationType(RegistrationType.REGISTERED);

    assertThatCode(() -> gate.requireAllowed(oneToOne)).doesNotThrowAnyException();
    verifyNoInteractions(lookup);
  }

  @Test
  void legacyRegisteredSessionWithoutModalityNeedsNoSwitch() {
    var legacy = new Session();
    legacy.setRegistrationType(RegistrationType.REGISTERED);

    assertThatCode(() -> gate.requireAllowed(legacy)).doesNotThrowAnyException();
    verifyNoInteractions(lookup);
  }

  @Test
  void liveChatOfAnEnabledCentreIsAllowed() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(new Settings().featureLiveChatFurtherCounsellingEnabled(true)));

    assertThatCode(() -> gate.requireAllowed(liveChat)).doesNotThrowAnyException();
  }

  @Test
  void liveChatOfACentreThatSwitchedItOffIsRefused() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(new Settings().featureLiveChatFurtherCounsellingEnabled(false)));

    assertFeatureDisabled();
  }

  @Test
  void liveChatOfACentreThatNeverSetItIsRefused() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(new Settings()));

    assertFeatureDisabled();
  }

  @Test
  void anonymousSessionWithoutLiveChatModalityIsGatedToo() {
    liveChat.setConversationType(null);
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(new Settings()));

    assertFeatureDisabled();
  }

  @Test
  void liveChatWithoutAgencyIsRefused() {
    liveChat.setAgencyId(null);

    assertFeatureDisabled();
    verifyNoInteractions(lookup);
  }

  @Test
  void centreSettingsThatAreNotServedAreRefused() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID)).thenReturn(Optional.empty());

    assertFeatureDisabled();
  }

  @Test
  void centreSettingsRefusedByTheAgencyServiceAreRefused() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ForbiddenException("refused"));

    assertFeatureDisabled();
  }

  @Test
  void clientErrorOfTheAgencyServiceIsRefused() {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x", null, null, null));

    assertFeatureDisabled();
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429})
  void transientClientErrorIsUnavailableNotAllowed(int status) {
    when(lookup.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.valueOf(status), "x", null, null, null));

    assertThatThrownBy(() -> gate.requireAllowed(liveChat))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void agencyServiceOutageIsUnavailableNotAllowed() {
    when(lookup.findEffectiveSettings(any(), any()))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThatThrownBy(() -> gate.requireAllowed(liveChat))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void agencyServiceServerErrorIsUnavailableNotAllowed() {
    when(lookup.findEffectiveSettings(any(), any()))
        .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null));

    assertThatThrownBy(() -> gate.requireAllowed(liveChat))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  private void assertFeatureDisabled() {
    assertThatThrownBy(() -> gate.requireAllowed(liveChat))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refused -> {
              org.assertj.core.api.Assertions.assertThat(refused.getHttpStatus())
                  .isEqualTo(HttpStatus.FORBIDDEN);
              org.assertj.core.api.Assertions.assertThat(
                      refused.getCustomHttpHeaders().get("X-Reason"))
                  .containsExactly(HttpStatusExceptionReason.FEATURE_DISABLED.name());
            });
  }
}
