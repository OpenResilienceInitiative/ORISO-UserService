package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

/** The mandatory common C guard must work even with no optional issuance bridge/configuration. */
class ProtectedInvitationDeliveryGuardTest {
  private final InviteAcceptUrlBuilder urls = mock(InviteAcceptUrlBuilder.class);
  private final InviteMailDispatchService sender = mock(InviteMailDispatchService.class);
  private final InviteEmailDeliveryFailureRecorder failures =
      mock(InviteEmailDeliveryFailureRecorder.class);
  private final InviteEmailDeliveryRepository audit = mock(InviteEmailDeliveryRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @Test
  void absentOptionalBridgeCannotMakeTheActualSpringProtectedRendererUseLegacyIssuance() {
    context("FIVE_ACTORS")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(ProtectedInvitationIssuance.class);
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(InviteDelivery.class)
                              .prepare(invite(), template(), LocalDateTime.now()))
                  .isInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(urls, sender, failures, audit);
            });
  }

  @Test
  void absentOptionalBridgeCannotSendAPreconstructedProtectedLinkThroughCommonDelivery() {
    context("FIVE_ACTORS")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(ProtectedInvitationIssuance.class);
              String token = "A".repeat(43);
              var prepared =
                  new InviteDelivery.Prepared(
                      invite(),
                      template(),
                      token,
                      "https://admin.example.test/invite/" + token,
                      "Invitation",
                      "Body",
                      LocalDateTime.now());
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(InviteDelivery.class)
                              .deliver(prepared, 73L, false, ignored -> {}))
                  .isInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(sender, failures, audit);
            });
  }

  @Test
  void legacySpringRendererStillProvidesTheExistingLinkWithoutAnIssuanceBridge() {
    context("LEGACY")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(ProtectedInvitationIssuance.class);
              var mail =
                  context
                      .getBean(InviteDelivery.class)
                      .prepare(invite(), template(), LocalDateTime.now());
              assertThat(mail.rawToken()).hasSize(43);
              assertThat(mail.acceptUrl()).endsWith(mail.rawToken());
              verifyNoInteractions(sender, failures, audit);
            });
  }

  private ApplicationContextRunner context(String model) {
    when(urls.buildAcceptUrl(any(), anyString()))
        .thenAnswer(call -> "https://admin.example.test/invite/" + call.getArgument(1));
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("guard@example.test", Instant.now()));
    return new ApplicationContextRunner()
        .withUserConfiguration(InviteDelivery.class)
        .withPropertyValues("TASK_IDENTITY_MODEL=" + model)
        .withBean(InviteAcceptUrlBuilder.class, () -> urls)
        .withBean(InviteMailDispatchService.class, () -> sender)
        .withBean(InviteEmailDeliveryFailureRecorder.class, () -> failures)
        .withBean(InviteEmailDeliveryRepository.class, () -> audit)
        .withBean(PlatformTransactionManager.class, () -> transactions);
  }

  private static AccountInvite invite() {
    return AccountInvite.builder()
        .id(73L)
        .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
        .tenantId(17L)
        .recipientEmail("guard@example.test")
        .firstName("Ada")
        .lastName("Lovelace")
        .status(AccountInviteStatus.DRAFT)
        .build();
  }

  private static InviteEmailTemplate template() {
    return InviteEmailTemplate.builder()
        .id(5L)
        .kind(InviteEmailTemplateKind.TENANT_INVITE)
        .name("Reviewed template")
        .subject("Invitation")
        .body("Body {{inviteLink}}")
        .language("de")
        .active(true)
        .createDate(LocalDateTime.now())
        .build();
  }
}
