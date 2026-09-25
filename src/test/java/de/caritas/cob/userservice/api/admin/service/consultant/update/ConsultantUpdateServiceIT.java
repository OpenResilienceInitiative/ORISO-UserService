package de.caritas.cob.userservice.api.admin.service.consultant.update;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.TopicPermissionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTopicPermissionService;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
public class ConsultantUpdateServiceIT extends ConsultantUpdateServiceBase {

  protected String VALID_CONSULTANT_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";

  @Test
  public void updateConsultant_Should_returnUpdatedPersistedConsultant_When_inputDataIsValid() {
    super.updateConsultant_Should_returnUpdatedPersistedConsultant_When_inputDataIsValid();
  }

  @Test
  public void updateConsultant_Should_persistPersonalInfo_With_nullUntouchedAndBlankClearing() {
    super.updateConsultant_Should_persistPersonalInfo_With_nullUntouchedAndBlankClearing();
  }

  @Test
  public void updateConsultant_Should_persistBothDisplayNames_With_nullUntouchedAndBlankClearing() {
    super.updateConsultant_Should_persistBothDisplayNames_With_nullUntouchedAndBlankClearing();
  }

  @Test
  public void updateConsultant_Should_persistAvatar_With_nullUntouchedAndBlankIdClearing() {
    super.updateConsultant_Should_persistAvatar_With_nullUntouchedAndBlankIdClearing();
  }

  @Test
  public void updateConsultant_Should_dropMotifId_When_kindIsNotIcon() {
    super.updateConsultant_Should_dropMotifId_When_kindIsNotIcon();
  }

  @Test
  public void updateConsultant_Should_throwCustomResponseException_When_absenceIsInvalid() {
    super.updateConsultant_Should_throwCustomResponseException_When_absenceIsInvalid();
  }

  @Test
  public void updateConsultant_Should_throwCustomResponseException_When_newEmailIsInvalid() {
    super.updateConsultant_Should_throwCustomResponseException_When_newEmailIsInvalid();
  }

  // --- topic permission ---------------------------------------------------------------------

  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private AccountInviteTopicPermissionService topicPermissionService;

  @AfterEach
  void resetTopicPermission() {
    accountInviteRepository.deleteAll();
    var consultant = consultantRepository.findById(VALID_CONSULTANT_ID).orElseThrow();
    consultant.setTopicPermission(TopicPermission.CREATE);
    consultantRepository.save(consultant);
  }

  @Test
  void updateConsultant_Should_setTheTopicPermission_And_keepItWhenTheRequestOmitsIt() {
    var withPermission = minimalUpdate();
    withPermission.setTopicPermission(TopicPermissionDTO.SELECT_EXISTING);

    assertThat(
        consultantUpdateService
            .updateConsultant(VALID_CONSULTANT_ID, withPermission)
            .getTopicPermission(),
        is(TopicPermission.SELECT_EXISTING));

    assertThat(
        consultantUpdateService
            .updateConsultant(VALID_CONSULTANT_ID, minimalUpdate())
            .getTopicPermission(),
        is(TopicPermission.SELECT_EXISTING));
  }

  @Test
  void inviteTable_Should_showTheCounsellorsOwnPermission_AfterAnUpdate() {
    var invite =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(1L)
                .recipientEmail("topic-permission-sync@example.org")
                .status(AccountInviteStatus.ACCEPTED)
                .provisioningStatus(AccountInviteProvisioningStatus.COMPLETED)
                .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
                .twoFactorStatus(TwoFactorGateStatus.ACTIVE)
                .provisionedUserId(VALID_CONSULTANT_ID)
                .topicPermission(TopicPermission.CREATE)
                .createDate(LocalDateTime.now())
                .build());
    var update = minimalUpdate();
    update.setTopicPermission(TopicPermissionDTO.NONE);

    consultantUpdateService.updateConsultant(VALID_CONSULTANT_ID, update);

    assertThat(
        topicPermissionService.currentPermissions(List.of(invite)).get(invite.getId()),
        is(TopicPermission.NONE));
  }

  private static UpdateAdminConsultantDTO minimalUpdate() {
    var update = new UpdateAdminConsultantDTO();
    update.setAbsent(false);
    update.setFirstname("first");
    update.setLastname("last");
    update.setEmail("topicpermission@address.de");
    update.formalLanguage(true);
    return update;
  }

  protected String getValidConsultantId() {
    return VALID_CONSULTANT_ID;
  }
}
