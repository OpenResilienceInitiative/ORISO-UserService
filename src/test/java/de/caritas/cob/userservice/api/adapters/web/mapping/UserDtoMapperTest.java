package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.EmailToggle;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailType;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.OtpType;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserDtoMapperTest {

  @Mock private AuthenticatedUser authenticatedUser;

  private UserDtoMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new UserDtoMapper();
    ReflectionTestUtils.setField(mapper, "appointmentFeatureEnabled", true);
  }

  @Test
  void userDataOf_Should_returnEnrichedUserData_When_otpInfoIsNull() {
    var userData = new UserDataResponseDTO();
    userData.setEncourage2fa(true);
    userData.setUserRoles(Set.of(UserRole.CONSULTANT.getValue()));

    var result = mapper.userDataOf(userData, null, true, true);

    assertThat(result.getTwoFactorAuth().getIsEnabled()).isFalse();
    assertThat(result.getTwoFactorAuth().getIsToEncourage()).isTrue();
    assertThat(result.getE2eEncryptionEnabled()).isTrue();
    assertThat(result.getIsDisplayNameEditable()).isTrue();
    assertThat(result.getAppointmentFeatureEnabled()).isTrue();
  }

  @Test
  void userDataOf_Should_markIsActive_When_otpIsSetupWithAppType() {
    var userData = new UserDataResponseDTO();
    userData.setUserRoles(Set.of(UserRole.USER.getValue()));
    var otp = new OtpInfoDTO();
    otp.setOtpSetup(true);
    otp.setOtpType(de.caritas.cob.userservice.api.model.OtpType.APP);
    otp.setOtpSecret("secret");
    otp.setOtpSecretQrCode("qr");

    var result = mapper.userDataOf(userData, otp, false, false);

    assertThat(result.getTwoFactorAuth().getIsEnabled()).isTrue();
    assertThat(result.getTwoFactorAuth().getIsActive()).isTrue();
    assertThat(result.getTwoFactorAuth().getType()).isEqualTo(OtpType.APP);
    assertThat(result.getTwoFactorAuth().getQrCode()).isEqualTo("qr");
    assertThat(result.getTwoFactorAuth().getSecret()).isEqualTo("secret");
    assertThat(result.getE2eEncryptionEnabled()).isFalse();
    assertThat(result.getIsDisplayNameEditable()).isFalse();
  }

  @Test
  void userDataOf_Should_setEmailType_When_otpTypeIsNotApp() {
    var userData = new UserDataResponseDTO();
    userData.setUserRoles(Set.of(UserRole.CONSULTANT.getValue()));
    var otp = new OtpInfoDTO();
    otp.setOtpSetup(true);
    otp.setOtpType(de.caritas.cob.userservice.api.model.OtpType.EMAIL);

    var result = mapper.userDataOf(userData, otp, true, true);

    assertThat(result.getTwoFactorAuth().getType()).isEqualTo(OtpType.EMAIL);
  }

  @Test
  void userDataOf_Should_notMarkActive_When_otpSetupIsFalse() {
    var userData = new UserDataResponseDTO();
    userData.setUserRoles(Set.of(UserRole.USER.getValue()));
    var otp = new OtpInfoDTO();
    otp.setOtpSetup(false);

    var result = mapper.userDataOf(userData, otp, false, true);

    assertThat(result.getTwoFactorAuth().getIsEnabled()).isTrue();
    assertThat(result.getTwoFactorAuth().getIsActive()).isFalse();
    assertThat(result.getIsDisplayNameEditable()).isFalse();
  }

  @Test
  void userDataOf_Should_notSetOtpType_When_setupIsTrueButTypeIsNull() {
    var userData = new UserDataResponseDTO();
    userData.setUserRoles(Set.of(UserRole.CONSULTANT.getValue()));
    var otp = new OtpInfoDTO();
    otp.setOtpSetup(true);
    otp.setOtpType(null);

    var result = mapper.userDataOf(userData, otp, false, false);

    assertThat(result.getTwoFactorAuth().getIsActive()).isTrue();
    assertThat(result.getTwoFactorAuth().getType()).isNull();
  }

  @Test
  void displayNameOf_Should_returnValue_When_keyPresent() {
    Map<String, Object> map = Map.of("displayName", "Alice");
    assertThat(mapper.displayNameOf(map)).isEqualTo("Alice");
  }

  @Test
  void displayNameOf_Should_returnNull_When_keyAbsent() {
    assertThat(mapper.displayNameOf(Map.of())).isNull();
  }

  @Test
  void chatUserIdOf_Should_returnValue_When_keyPresent() {
    assertThat(mapper.chatUserIdOf(Map.of("chatUserId", "rc-1"))).isEqualTo("rc-1");
  }

  @Test
  void chatUserIdOf_Should_returnNull_When_keyAbsent() {
    assertThat(mapper.chatUserIdOf(Map.of())).isNull();
  }

  @Test
  void preferredLanguageOf_Should_returnLanguage_When_present() {
    var dto = new PatchUserDTO();
    dto.setPreferredLanguage(LanguageCode.DE);

    assertThat(mapper.preferredLanguageOf(dto)).hasValue("de");
  }

  @Test
  void preferredLanguageOf_Should_returnEmpty_When_null() {
    assertThat(mapper.preferredLanguageOf(new PatchUserDTO())).isEmpty();
  }

  @Test
  void availableOf_Should_wrapValue() {
    var dto = new PatchUserDTO();
    dto.setAvailable(true);
    assertThat(mapper.availableOf(dto)).hasValue(true);
  }

  @Test
  void availableOf_Should_returnEmpty_When_null() {
    assertThat(mapper.availableOf(new PatchUserDTO())).isEmpty();
  }

  @Test
  void mapOf_Should_returnEmpty_When_allFieldsNull() {
    var dto = new PatchUserDTO();
    dto.setEmailToggles(null);

    var result = mapper.mapOf(dto, authenticatedUser);

    assertThat(result).isEmpty();
  }

  @Test
  void mapOf_Should_populateAllProvidedFields() {
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    var dto = new PatchUserDTO();
    dto.setEncourage2fa(true);
    dto.setDisplayName("Alice");
    dto.setMagicLinkLoginEnabled(true);
    dto.setWalkThroughEnabled(false);
    dto.setPreferredLanguage(LanguageCode.EN);
    dto.setTermsAndConditionsConfirmation(true);
    dto.setDataPrivacyConfirmation(true);
    dto.setAvailable(false);
    var toggleDaily = new EmailToggle();
    toggleDaily.setName(EmailType.DAILY_ENQUIRY);
    toggleDaily.setState(true);
    var toggleNewChat = new EmailToggle();
    toggleNewChat.setName(EmailType.NEW_CHAT_MESSAGE_FROM_ADVICE_SEEKER);
    toggleNewChat.setState(false);
    dto.setEmailToggles(Set.of(toggleDaily, toggleNewChat));

    var result = mapper.mapOf(dto, authenticatedUser);

    assertThat(result).isPresent();
    Map<String, Object> map = result.get();
    assertThat(map)
        .containsEntry("id", "u-1")
        .containsEntry("encourage2fa", true)
        .containsEntry("displayName", "Alice")
        .containsEntry("magicLinkLoginEnabled", true)
        .containsEntry("walkThroughEnabled", false)
        .containsEntry("preferredLanguage", "en")
        .containsEntry("termsAndConditionsConfirmation", true)
        .containsEntry("dataPrivacyConfirmation", true)
        .containsEntry("available", false)
        .containsEntry("notifyEnquiriesRepeating", true)
        .containsEntry("notifyNewChatMessageFromAdviceSeeker", false);
  }

  @Test
  void mapOf_emailAndUser_Should_returnMap() {
    when(authenticatedUser.getUserId()).thenReturn("u-9");

    var result = mapper.mapOf("me@example.org", authenticatedUser);

    assertThat(result).containsEntry("id", "u-9").containsEntry("email", "me@example.org");
  }

  @Test
  void bannedChatUserIdsOf_Should_returnMutedUsers() {
    Map<String, Object> chatMeta = new HashMap<>();
    chatMeta.put("mutedUsers", List.of("a", "b"));

    assertThat(mapper.bannedChatUserIdsOf(chatMeta)).containsExactly("a", "b");
  }

  @Test
  void bannedChatUserIdsOf_Should_returnNull_When_keyMissing() {
    assertThat(mapper.bannedChatUserIdsOf(Map.of())).isNull();
  }
}
