package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Appointment;
import de.caritas.cob.userservice.api.model.Appointment.AppointmentStatus;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceMapperTest {

  @InjectMocks private UserServiceMapper userServiceMapper;

  @Mock private UsernameTranscoder usernameTranscoder;

  @Test
  void saveWalkThroughEnabled() {
    Map<String, Object> requestData = new HashMap<>();
    requestData.put("walkThroughEnabled", true);
    requestData.put("id", "1");
    Consultant consultant = new Consultant();

    userServiceMapper.consultantOf(consultant, requestData);

    assertThat(consultant.getWalkThroughEnabled()).isTrue();
  }

  @Test
  void saveNotificationsEnabled() {
    Map<String, Object> requestData = new HashMap<>();
    NotificationsSettingsDTO allActiveSettings =
        new NotificationsSettingsDTO()
            .appointmentNotificationEnabled(true)
            .reassignmentNotificationEnabled(true)
            .newChatMessageNotificationEnabled(true)
            .appointmentNotificationEnabled(true);
    EmailNotificationsDTO emailNotificationsDTO =
        new EmailNotificationsDTO().emailNotificationsEnabled(true).settings(allActiveSettings);
    requestData.put("emailNotifications", emailNotificationsDTO);
    requestData.put("id", "1");
    Consultant consultant = new Consultant();

    userServiceMapper.consultantOf(consultant, requestData);

    assertThat(consultant.isNotificationsEnabled()).isTrue();
    assertThat(consultant.getNotificationsSettings())
        .isEqualTo(
            "{\"initialEnquiryNotificationEnabled\":false,\"newChatMessageNotificationEnabled\":true,\"reassignmentNotificationEnabled\":true,\"appointmentNotificationEnabled\":true}");
  }

  @Test
  void e2eKeyOfShouldMapIfKeyExists() {
    var map = Map.of("e2eKey", "tmp." + RandomStringUtils.randomAlphanumeric(16));

    var e2eKey = userServiceMapper.e2eKeyOf(map);

    assertThat(e2eKey).isPresent();
    assertThat(map).containsEntry("e2eKey", e2eKey.get());
  }

  @Test
  void e2eKeyOfShouldNotMapIfKeyFormatIsWrong() {
    var map = Map.of("e2eKey", RandomStringUtils.randomAlphanumeric(16));

    var e2eKey = userServiceMapper.e2eKeyOf(map);

    assertThat(e2eKey).isNotPresent();
  }

  @Test
  void e2eKeyOfShouldNotMapIfKeyDoesNotExist() {
    var map = Map.of("notE2eKey", RandomStringUtils.randomAlphanumeric(16));

    var e2eKey = userServiceMapper.e2eKeyOf(map);

    assertThat(e2eKey).isNotPresent();
  }

  // ── mapOf(Appointment) ────────────────────────────────────────────────────

  @Test
  void mapOf_Appointment_Should_MapAllFields() {
    Consultant consultant = new Consultant();
    consultant.setId("c-1");

    Appointment appointment = new Appointment();
    appointment.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    appointment.setDescription("Test session");
    appointment.setDatetime(Instant.parse("2026-07-01T10:00:00Z"));
    appointment.setStatus(AppointmentStatus.CREATED);
    appointment.setConsultant(consultant);

    Map<String, Object> result = userServiceMapper.mapOf(appointment);

    assertThat(result.get("id")).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(result.get("description")).isEqualTo("Test session");
    assertThat(result.get("status")).isEqualTo("created");
    assertThat(result.get("consultantId")).isEqualTo("c-1");
  }

  // ── mapOf(User) ───────────────────────────────────────────────────────────

  @Test
  void mapOf_User_Should_MapAllFields() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("user@example.com");
    user.setEncourage2fa(true);
    user.setMagicLinkLoginEnabled(false);
    user.setRcUserId("rc-u-1");
    user.setLanguageCode(LanguageCode.de);

    Map<String, Object> result = userServiceMapper.mapOf(user);

    assertThat(result.get("id")).isEqualTo("u-1");
    assertThat(result.get("username")).isEqualTo("testuser");
    assertThat(result.get("email")).isEqualTo("user@example.com");
    assertThat(result.get("encourage2fa")).isEqualTo(true);
    assertThat(result.get("chatUserId")).isEqualTo("rc-u-1");
    assertThat(result.get("preferredLanguage")).isEqualTo("de");
  }

  // ── mapOf(Consultant, additionalMap) ──────────────────────────────────────

  @Test
  void mapOf_Consultant_Should_MapAllFields() {
    when(usernameTranscoder.decodeUsername("Encoded Name")).thenReturn("Decoded Name");

    Consultant consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setFirstName("John");
    consultant.setLastName("Doe");
    consultant.setEmail("john@example.com");
    consultant.setEncourage2fa(false);
    consultant.setMagicLinkLoginEnabled(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(false);
    consultant.setWalkThroughEnabled(true);
    consultant.setRocketChatId("rc-c-1");
    consultant.setLanguageCode(LanguageCode.en);
    consultant.setDisplayName("Default Name");

    Map<String, Object> additionalMap = Map.of("displayName", "Encoded Name");
    Map<String, Object> result = userServiceMapper.mapOf(consultant, additionalMap);

    assertThat(result.get("id")).isEqualTo("c-1");
    assertThat(result.get("firstName")).isEqualTo("John");
    assertThat(result.get("email")).isEqualTo("john@example.com");
    assertThat(result.get("displayName")).isEqualTo("Decoded Name");
    assertThat(result.get("preferredLanguage")).isEqualTo("en");
  }

  @Test
  void mapOf_Consultant_Should_UseConsultantDisplayName_When_NotInAdditionalMap() {
    when(usernameTranscoder.decodeUsername("Native Name")).thenReturn("Native Name");

    Consultant consultant = new Consultant();
    consultant.setId("c-2");
    consultant.setFirstName("Jane");
    consultant.setLastName("Smith");
    consultant.setEmail("jane@example.com");
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setDisplayName("Native Name");

    Map<String, Object> result = userServiceMapper.mapOf(consultant, Map.of());

    assertThat(result.get("displayName")).isEqualTo("Native Name");
  }

  // ── mapOf(Optional<Session>) ──────────────────────────────────────────────

  @Test
  void mapOf_OptionalSession_Should_ReturnEmpty_When_SessionNotPresent() {
    assertThat(userServiceMapper.mapOf(Optional.<Session>empty())).isEmpty();
  }

  @Test
  void mapOf_OptionalSession_Should_MapFields_When_SessionPresent() {
    User user = new User();
    user.setUserId("u-1");

    Session session = new Session();
    session.setUser(user);
    session.setGroupId("rc-group-1");
    session.setStatus(SessionStatus.NEW);
    session.setConsultingTypeId(1);
    session.setAgencyId(10L);
    session.setMainTopicId(5L);
    session.setRegistrationType(RegistrationType.REGISTERED);

    Map<String, Object> result = userServiceMapper.mapOf(Optional.of(session)).orElseThrow();

    assertThat(result.get("chatId")).isEqualTo("rc-group-1");
    assertThat(result.get("adviceSeekerId")).isEqualTo("u-1");
    assertThat(result.get("agencyId")).isEqualTo(10L);
    assertThat(result.get("mainTopicId")).isEqualTo(5L);
  }

  @Test
  void mapOf_OptionalSession_Should_NotIncludeNullGroupId() {
    User user = new User();
    user.setUserId("u-2");

    Session session = new Session();
    session.setUser(user);
    session.setGroupId(null);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setConsultingTypeId(2);
    session.setRegistrationType(RegistrationType.ANONYMOUS);

    Map<String, Object> result = userServiceMapper.mapOf(Optional.of(session)).orElseThrow();

    assertThat(result.containsKey("chatId")).isFalse();
  }

  // ── statusOf ──────────────────────────────────────────────────────────────

  @Test
  void statusOf_Should_ReturnOnline_When_Available() {
    assertThat(userServiceMapper.statusOf(true)).isEqualTo("online");
  }

  @Test
  void statusOf_Should_ReturnBusy_When_NotAvailable() {
    assertThat(userServiceMapper.statusOf(false)).isEqualTo("busy");
  }

  // ── bannedUsernamesOfMap / chatUserIdOf ───────────────────────────────────

  @Test
  void bannedUsernamesOfMap_Should_ReturnMutedUsers() {
    Map<String, Object> map = Map.of("mutedUsers", List.of("user1", "user2"));

    assertThat(userServiceMapper.bannedUsernamesOfMap(map)).containsExactly("user1", "user2");
  }

  @Test
  void chatUserIdOf_Should_ExtractChatUserIds() {
    List<Map<String, String>> members =
        List.of(Map.of("chatUserId", "u1"), Map.of("chatUserId", "u2"));

    assertThat(userServiceMapper.chatUserIdOf(members)).containsExactly("u1", "u2");
  }

  // ── encodedDisplayNameOf / displayNameOf ──────────────────────────────────

  @Test
  void encodedDisplayNameOf_Should_ReturnEncoded_When_DisplayNamePresent() {
    when(usernameTranscoder.encodeUsername("John Doe")).thenReturn("John+Doe");

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("displayName", "John Doe");

    assertThat(userServiceMapper.encodedDisplayNameOf(patchMap)).contains("John+Doe");
  }

  @Test
  void encodedDisplayNameOf_Should_ReturnEmpty_When_NoDisplayName() {
    assertThat(userServiceMapper.encodedDisplayNameOf(Map.of())).isEmpty();
  }

  @Test
  void displayNameOf_Should_ReturnName_When_Present() {
    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("displayName", "Jane Doe");

    assertThat(userServiceMapper.displayNameOf(patchMap)).contains("Jane Doe");
  }

  @Test
  void displayNameOf_Should_ReturnEmpty_When_NotPresent() {
    assertThat(userServiceMapper.displayNameOf(Map.of())).isEmpty();
  }

  // ── consultantOf patch fields ─────────────────────────────────────────────

  @Test
  void consultantOf_Should_UpdateOnlyFieldsPresentInPatchMap() {
    Consultant consultant = new Consultant();
    consultant.setEmail("old@example.com");
    consultant.setFirstName("Old");

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("email", "new@example.com");

    Consultant result = userServiceMapper.consultantOf(consultant, patchMap);

    assertThat(result.getEmail()).isEqualTo("new@example.com");
    assertThat(result.getFirstName()).isEqualTo("Old");
  }

  @Test
  void consultantOf_Should_SetTermsConfirmation_When_TrueInPatchMap() {
    Consultant consultant = new Consultant();

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("termsAndConditionsConfirmation", Boolean.TRUE);

    Consultant result = userServiceMapper.consultantOf(consultant, patchMap);

    assertThat(result.getTermsAndConditionsConfirmation()).isNotNull();
  }

  @Test
  void consultantOf_Should_UpdatePreferredLanguage_When_InPatchMap() {
    Consultant consultant = new Consultant();

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("preferredLanguage", "de");

    Consultant result = userServiceMapper.consultantOf(consultant, patchMap);

    assertThat(result.getLanguageCode()).isEqualTo(LanguageCode.de);
  }

  // ── adviceSeekerOf patch ──────────────────────────────────────────────────

  @Test
  void adviceSeekerOf_Should_UpdateFieldsFromPatchMap() {
    User user = new User();
    user.setEmail("old@example.com");

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("email", "new@example.com");
    patchMap.put("encourage2fa", false);
    patchMap.put("preferredLanguage", "en");

    User result = userServiceMapper.adviceSeekerOf(user, patchMap);

    assertThat(result.getEmail()).isEqualTo("new@example.com");
    assertThat(result.getEncourage2fa()).isFalse();
    assertThat(result.getLanguageCode()).isEqualTo(LanguageCode.en);
  }

  @Test
  void adviceSeekerOf_Should_SetDataPrivacyConfirmation_When_TrueInPatchMap() {
    User user = new User();

    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("dataPrivacyConfirmation", Boolean.TRUE);

    User result = userServiceMapper.adviceSeekerOf(user, patchMap);

    assertThat(result.getDataPrivacyConfirmation()).isNotNull();
  }

  // ── appointmentOf ─────────────────────────────────────────────────────────

  @Test
  void appointmentOf_Should_MapAllFields() {
    Consultant consultant = new Consultant();
    consultant.setId("c-1");

    Map<String, Object> appointmentMap = new HashMap<>();
    appointmentMap.put("id", "22222222-2222-2222-2222-222222222222");
    appointmentMap.put("description", "Counseling session");
    appointmentMap.put("datetime", "2026-08-01T09:00:00Z");
    appointmentMap.put("status", "created");

    Appointment result = userServiceMapper.appointmentOf(appointmentMap, consultant);

    assertThat(result.getId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    assertThat(result.getDescription()).isEqualTo("Counseling session");
    assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CREATED);
    assertThat(result.getConsultant()).isSameAs(consultant);
  }

  // ── roomIdOf / userIdOf ───────────────────────────────────────────────────

  @Test
  void roomIdOf_Should_ReturnRoomId() {
    assertThat(userServiceMapper.roomIdOf(Map.of("roomId", "!room:matrix.org")))
        .isEqualTo("!room:matrix.org");
  }

  @Test
  void userIdOf_Should_ReturnUserId() {
    assertThat(userServiceMapper.userIdOf(Map.of("userId", "@user:matrix.org")))
        .isEqualTo("@user:matrix.org");
  }
}
