package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** The last guard on every send path: a mail whose subject or body renders empty never leaves. */
@ExtendWith(MockitoExtension.class)
class InviteDeliveryTest {

  @Mock private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;
  @Mock private InviteMailDispatchService inviteMailDispatchService;
  @Mock private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;
  @Mock private InviteEmailDeliveryRepository deliveryRepository;
  @Mock private PlatformTransactionManager transactionManager;

  @InjectMocks private InviteDelivery delivery;

  private final AccountInvite invite =
      AccountInvite.builder()
          .id(1L)
          .recipientEmail("new@example.org")
          .targetRole(AccountInviteTargetRole.COUNSELLOR)
          .build();

  @BeforeEach
  void setUp() {
    when(inviteAcceptUrlBuilder.buildAcceptUrl(any(), any())).thenReturn("https://app/invite/x");
  }

  @Test
  void prepare_Should_Refuse_When_SubjectRendersEmpty() {
    // {{firstName}} is empty for an invite without a first name.
    var template = template("{{firstName}}", "Hello");

    assertThatThrownBy(() -> delivery.prepare(invite, template, LocalDateTime.now()))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inviteMailDispatchService, deliveryRepository);
  }

  @Test
  void prepare_Should_Refuse_When_BodyRendersEmpty() {
    var template = template("Welcome", "{{inviteLink}}");

    assertThatThrownBy(() -> delivery.prepare(invite, template, LocalDateTime.now()))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inviteMailDispatchService, deliveryRepository);
  }

  @Test
  void prepare_Should_RenderTheMail_When_SubjectAndBodyCarryText() {
    var prepared =
        delivery.prepare(invite, template("Welcome", "Hello {{email}}"), LocalDateTime.now());

    assertThat(prepared.subject()).isEqualTo("Welcome");
    assertThat(prepared.body()).isEqualTo("Hello new@example.org");
  }

  private static InviteEmailTemplate template(String subject, String body) {
    return InviteEmailTemplate.builder()
        .id(5L)
        .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
        .subject(subject)
        .body(body)
        .active(true)
        .build();
  }
}
