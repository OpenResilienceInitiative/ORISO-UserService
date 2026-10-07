package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailOrigin;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ExistingAccountSetupIssuerTest {
  @Mock private AccountInviteRepository invites;
  @Mock private InviteEmailDeliveryRepository deliveries;
  @Mock private AdminRepository admins;
  @Mock private ConsultantRepository consultants;
  @Mock private AdminScope adminScope;
  @Mock private AuthenticatedUser actor;
  @Mock private ExistingAccountSetupService confirmation;
  @Mock private InitialPasswordVerifier initialPasswords;
  @Mock private InviteAcceptUrlBuilder urls;
  @Mock private EmailBrandingResolver branding;
  @Mock private TenantEmailBrandValues brandValues;
  @Mock private OrisoEmailRenderer renderer;
  @Mock private InviteMailDispatchService mail;
  @Mock private PlatformTransactionManager transactions;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      provisioning;

  @InjectMocks private ExistingAccountSetupIssuer issuer;

  private final AtomicLong nextId = new AtomicLong(11);
  private final AtomicReference<AccountInvite> newRow = new AtomicReference<>();

  @BeforeEach
  void transactions() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        issuer, "provisioning", provisioning);
    lenient()
        .when(transactions.getTransaction(any()))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
  }

  @Test
  void directCreationPersistsOnlyVerifierAndNeverSnapshotsInitialSecretOrSetupUrl() {
    readyToSend();
    when(provisioning.setupProjection("admin-11"))
        .thenReturn(
            new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
                .AccountProjection(
                "admin-11",
                "admin@example.org",
                "admin@example.org",
                null,
                null,
                42L,
                "de",
                true,
                true,
                java.util.List.of("tenant-admin"),
                true));
    when(invites.findByActiveSetupIdentityKey("admin-11")).thenReturn(Optional.empty());
    when(initialPasswords.encode("initial-secret")).thenReturn("salted-verifier");

    issuer.issueAfterCreation(AccountInviteTargetRole.TENANT_ADMIN, "admin-11", "initial-secret");

    var invite = savedInvite();
    assertThat(invite.getProvisionedUserId()).isEqualTo("admin-11");
    assertThat(invite.getInitialPasswordVerifier()).isEqualTo("salted-verifier");
    assertThat(invite.toString()).doesNotContain("initial-secret", "salted-verifier");
    var delivery = ArgumentCaptor.forClass(InviteEmailDelivery.class);
    verify(deliveries).saveAndFlush(delivery.capture());
    assertThat(delivery.getValue().getSubjectSnapshot()).isEqualTo("konto-einrichten");
    assertThat(delivery.getValue().getBodySnapshot())
        .isEqualTo("konto-einrichten")
        .doesNotContain("initial-secret", "secret-link");
  }

  @Test
  void protectedResendReplacesExpiredTokenAndTransfersOnlyThePrivateVerifier() {
    readyToSend();
    var previous =
        AccountInvite.builder()
            .id(10L)
            .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .tenantId(42L)
            .recipientEmail("admin@example.org")
            .setupBoundUsername("admin@example.org")
            .provisionedUserId("admin-11")
            .activeSetupIdentityKey("admin-11")
            .initialPasswordVerifier("salted-verifier")
            .status(AccountInviteStatus.EXPIRED)
            .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(invites.findByActiveSetupIdentityKey("admin-11")).thenReturn(Optional.of(previous));

    issuer.reissue(AccountInviteTargetRole.TENANT_ADMIN, "admin-11");

    verify(adminScope).assertMay(AdminScope.Target.admin("admin-11"));
    verify(confirmation, org.mockito.Mockito.times(2))
        .requireSameCurrentIdentity(
            "admin-11",
            AccountInviteTargetRole.TENANT_ADMIN,
            42L,
            "admin@example.org",
            "admin@example.org");
    assertThat(previous.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(previous.getActiveSetupIdentityKey()).isNull();
    assertThat(previous.getInitialPasswordVerifier()).isNull();
    assertThat(savedInvite().getInitialPasswordVerifier()).isEqualTo("salted-verifier");
    verify(initialPasswords, never()).encode(any());
  }

  @Test
  void permanentPasswordStateStopsReissueBeforeSupersedingTheOldLink() {
    when(admins.findByIdAndType("admin-11", Admin.AdminType.TENANT))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("admin-11")
                    .type(Admin.AdminType.TENANT)
                    .tenantId(42L)
                    .firstName("A")
                    .lastName("D")
                    .username("admin@example.org")
                    .email("admin@example.org")
                    .build()));
    doThrow(new ConflictException("Existing account no longer requires this setup link"))
        .when(confirmation)
        .requireSameCurrentIdentity(
            "admin-11",
            AccountInviteTargetRole.TENANT_ADMIN,
            42L,
            "admin@example.org",
            "admin@example.org");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> issuer.reissue(AccountInviteTargetRole.TENANT_ADMIN, "admin-11"))
        .isInstanceOf(ConflictException.class);
    verify(adminScope).assertMay(AdminScope.Target.admin("admin-11"));
    verify(invites, never()).findByActiveSetupIdentityKey(any());
    verify(invites, never()).saveAndFlush(any());
  }

  @Test
  void staleSelectedInviteIdCannotReissueANewerSetupLink() {
    when(admins.findByIdAndType("admin-11", Admin.AdminType.TENANT))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("admin-11")
                    .type(Admin.AdminType.TENANT)
                    .tenantId(42L)
                    .firstName("A")
                    .lastName("D")
                    .username("admin@example.org")
                    .email("admin@example.org")
                    .build()));
    when(invites.findByActiveSetupIdentityKey("admin-11"))
        .thenReturn(Optional.of(AccountInvite.builder().id(12L).build()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                issuer.reissueSelectedInvite(AccountInviteTargetRole.TENANT_ADMIN, "admin-11", 10L))
        .isInstanceOf(ConflictException.class);
    verify(invites, never()).saveAndFlush(any());
    verifyNoInteractions(mail);
  }

  private void readyToSend() {
    when(admins.findByIdAndType("admin-11", Admin.AdminType.TENANT))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("admin-11")
                    .type(Admin.AdminType.TENANT)
                    .tenantId(42L)
                    .firstName("A")
                    .lastName("D")
                    .username("admin@example.org")
                    .email("admin@example.org")
                    .build()));
    when(invites.saveAndFlush(any(AccountInvite.class)))
        .thenAnswer(
            invocation -> {
              AccountInvite row = invocation.getArgument(0);
              if (row.getId() == null) {
                row.setId(nextId.getAndIncrement());
                newRow.set(row);
              }
              return row;
            });
    when(invites.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.of(savedInvite()));
    when(urls.buildAcceptUrl(eq(AccountInviteTargetRole.TENANT_ADMIN), any()))
        .thenReturn("https://app.example.org/secret-link");
    var resolved = new EmailBranding("Product", null, "#124078", null, null);
    when(branding.resolve(42L)).thenReturn(resolved);
    when(brandValues.values(resolved, 42L)).thenReturn(new HashMap<>());
    when(renderer.render(eq("konto-einrichten"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    when(mail.sendRendered(
            eq("admin@example.org"),
            any(),
            eq(InviteMailOrigin.of(42L, TenantSystemEmailDelivery.Purpose.ACCOUNT_INVITE))))
        .thenReturn(new InviteMailSendReceipt("admin@example.org", Instant.now()));
  }

  private AccountInvite savedInvite() {
    return java.util.Objects.requireNonNull(newRow.get());
  }
}
