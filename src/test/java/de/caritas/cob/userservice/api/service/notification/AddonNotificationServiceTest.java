package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonNotificationRequestDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.AddonNotificationService.RecipientKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddonNotificationServiceTest {

  private static final String ASKER_ID = "8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10";
  private static final String CONSULTANT_ID = "c0ffee00-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private AddonNotificationMailer mailer;
  @InjectMocks private AddonNotificationService service;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    var asker = new User();
    asker.setUserId(ASKER_ID);
    asker.setTenantId(1L);
    when(userRepository.findByUserIdAndDeleteDateIsNull(ASKER_ID)).thenReturn(Optional.of(asker));
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setTenantId(1L);
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    when(eventNotificationService.createAddonNotification(
            anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(42L);
  }

  @Test
  void storesTheEventWithTheFullPayloadAsParams() throws Exception {
    var id = service.create(askerRequest(true));

    assertThat(id).isEqualTo(42L);
    var params = ArgumentCaptor.forClass(String.class);
    verify(eventNotificationService)
        .createAddonNotification(
            eq(ASKER_ID),
            eq(1L),
            eq("Neues Tool freigegeben"),
            eq("Ihre Beraterin hat ein Tool freigegeben."),
            params.capture(),
            eq("/addons/digisucht-tools/goals"),
            isNull());
    JsonNode json = objectMapper.readTree(params.getValue());
    assertThat(json.get("addon").asText()).isEqualTo("digisucht-tools");
    assertThat(json.get("appId").asText()).isEqualTo("goals");
    assertThat(json.get("slot").asText()).isEqualTo("asker.navigation");
    assertThat(json.get("sessionId").isNull()).isTrue();
    assertThat(json.get("important").asBoolean()).isTrue();
    assertThat(json.get("title").get("en").asText()).isEqualTo("New tool released");
    assertThat(json.get("text").get("de").asText())
        .isEqualTo("Ihre Beraterin hat ein Tool freigegeben.");
  }

  @Test
  void importantNotificationAsksTheMailerWhichAppliesThePreferences() {
    service.create(askerRequest(true));

    verify(mailer).sendIfWanted(RecipientKind.ASKER, ASKER_ID, 1L);
  }

  @Test
  void unimportantNotificationNeverReachesTheMailer() {
    service.create(askerRequest(false));

    verifyNoInteractions(mailer);
  }

  @Test
  void consultantSessionSlotKeepsTheSessionAndLeavesThePathToTheClient() {
    service.create(
        request(CONSULTANT_ID, 1L, "consultant.session", 7L, false, de("Titel"), de("Text")));

    verify(eventNotificationService)
        .createAddonNotification(
            eq(CONSULTANT_ID), eq(1L), eq("Titel"), eq("Text"), anyString(), isNull(), eq(7L));
  }

  @Test
  void unknownRecipientIsNotFound() {
    assertThatThrownBy(
            () ->
                service.create(
                    request("unknown", 1L, "asker.navigation", null, true, de("T"), de("X"))))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(mailer);
  }

  @Test
  void recipientOfAnotherTenantIsRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    request(ASKER_ID, 2L, "asker.navigation", null, true, de("T"), de("X"))))
        .isInstanceOf(BadRequestException.class);
    verify(eventNotificationService, never())
        .createAddonNotification(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void recipientWithoutTenantCountsAsLegacyTenantOne() {
    var legacy = new User();
    legacy.setUserId("legacy");
    when(userRepository.findByUserIdAndDeleteDateIsNull("legacy")).thenReturn(Optional.of(legacy));

    service.create(request("legacy", 1L, "asker.navigation", null, false, de("T"), de("X")));

    verify(eventNotificationService)
        .createAddonNotification(eq("legacy"), eq(1L), any(), any(), any(), any(), any());
  }

  @Test
  void slotMustFitTheRecipientsRole() {
    assertThatThrownBy(
            () ->
                service.create(
                    request(ASKER_ID, 1L, "consultant.navigation", null, false, de("T"), de("X"))))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sessionSlotsNeedASessionId() {
    assertThatThrownBy(
            () ->
                service.create(
                    request(
                        CONSULTANT_ID,
                        1L,
                        "consultant.asker-profile",
                        null,
                        false,
                        de("T"),
                        de("X"))))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void rejectsUnknownSlotsAndMalformedNames() {
    assertBadRequest(request(ASKER_ID, 1L, "admin.page", null, false, de("T"), de("X")));
    assertBadRequest(
        new AddonNotificationRequestDTO(
            ASKER_ID, 1L, "Bad Name", "goals", "asker.navigation", null, false, de("T"), de("X")));
    assertBadRequest(
        new AddonNotificationRequestDTO(
            ASKER_ID,
            1L,
            "digisucht-tools",
            "../x",
            "asker.navigation",
            null,
            false,
            de("T"),
            de("X")));
    assertBadRequest(request(ASKER_ID, null, "asker.navigation", null, false, de("T"), de("X")));
  }

  @Test
  void germanTitleAndTextAreRequired() {
    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, Map.of("en", "Title"), de("Text")));
    assertBadRequest(request(ASKER_ID, 1L, "asker.navigation", null, false, de("Title"), Map.of()));
  }

  @Test
  void enforcesTheLengthLimits() {
    service.create(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("t".repeat(120)), de("x")));
    service.create(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("t"), de("x".repeat(500))));

    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("t".repeat(121)), de("x")));
    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("t"), de("x".repeat(501))));
  }

  @Test
  void rejectsMarkupAndControlCharacters() {
    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("<b>Neu</b>"), de("x")));
    assertBadRequest(
        request(
            ASKER_ID,
            1L,
            "asker.navigation",
            null,
            false,
            de("Titel"),
            de("<img src=x onerror=alert(1)>")));
    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("Zeile\nZwei"), de("x")));
    assertBadRequest(
        request(ASKER_ID, 1L, "asker.navigation", null, false, de("Titel"), de("a‮b")));
  }

  @Test
  void textMayContainLineBreaksAndIsTrimmed() {
    service.create(
        request(
            ASKER_ID,
            1L,
            "asker.navigation",
            null,
            false,
            de("  „Ziele“ freigegeben "),
            de("Erste Zeile\r\nZweite Zeile")));

    verify(eventNotificationService)
        .createAddonNotification(
            any(),
            any(),
            eq("„Ziele“ freigegeben"),
            eq("Erste Zeile\nZweite Zeile"),
            any(),
            any(),
            any());
  }

  private void assertBadRequest(AddonNotificationRequestDTO request) {
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BadRequestException.class);
  }

  private static AddonNotificationRequestDTO askerRequest(boolean important) {
    var title = new LinkedHashMap<String, String>();
    title.put("de", "Neues Tool freigegeben");
    title.put("en", "New tool released");
    return request(
        ASKER_ID,
        1L,
        "asker.navigation",
        null,
        important,
        title,
        de("Ihre Beraterin hat ein Tool freigegeben."));
  }

  private static AddonNotificationRequestDTO request(
      String userId,
      Long tenantId,
      String slot,
      Long sessionId,
      boolean important,
      Map<String, String> title,
      Map<String, String> text) {
    return new AddonNotificationRequestDTO(
        userId, tenantId, "digisucht-tools", "goals", slot, sessionId, important, title, text);
  }

  private static Map<String, String> de(String value) {
    return Map.of("de", value);
  }
}
