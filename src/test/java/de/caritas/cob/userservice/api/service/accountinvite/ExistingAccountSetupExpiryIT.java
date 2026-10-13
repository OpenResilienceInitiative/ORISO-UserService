package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordChangeRequirement;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the real expiry, protected issuer, claim and completion against committed H2 rows. */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditingConfiguration.class)
class ExistingAccountSetupExpiryIT {

  private static final String ACCOUNT_ID = "admin-11";
  private static final String EMAIL = "admin@example.org";
  private static final String OLD_TOKEN = "expired-setup-token";

  @Autowired private AccountInviteRepository invites;
  @Autowired private PlatformTransactionManager transactions;

  @AfterEach
  void cleanUp() {
    invites.deleteAll();
  }

  @Test
  void expiredSetupCanBeReissuedAndOnlyItsNewLinkCanCompleteOnce() {
    var admin =
        Admin.builder()
            .id(ACCOUNT_ID)
            .type(Admin.AdminType.TENANT)
            .tenantId(42L)
            .firstName("A")
            .lastName("D")
            .username(EMAIL)
            .email(EMAIL)
            .build();
    var admins = mock(AdminRepository.class);
    when(admins.findByIdAndType(ACCOUNT_ID, Admin.AdminType.TENANT)).thenReturn(Optional.of(admin));
    var consultants = mock(ConsultantRepository.class);
    var verifier = new InitialPasswordVerifier();
    String privateHash = verifier.encode("initial-secret");
    AccountInvite old =
        invites.saveAndFlush(
            AccountInvite.builder()
                .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
                .tenantId(42L)
                .recipientEmail(EMAIL)
                .setupBoundUsername(EMAIL)
                .provisionedUserId(ACCOUNT_ID)
                .activeSetupIdentityKey(ACCOUNT_ID)
                .initialPasswordVerifier(privateHash)
                .tokenHash(AccountInviteService.hash(OLD_TOKEN))
                .status(AccountInviteStatus.EMAIL_SENT)
                .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .createDate(LocalDateTime.now().minusDays(2))
                .build());

    new TransactionTemplate(transactions)
        .execute(
            status -> {
              assertThat(
                      invites.expireElapsedExistingAccountSetup(
                          AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
                          List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT),
                          AccountInviteStatus.EXPIRED,
                          AccountInviteProvisioningStatus.IN_PROGRESS,
                          LocalDateTime.now()))
                  .isEqualTo(1);
              return null;
            });
    assertThat(invites.findById(old.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.EXPIRED);

    var identities = mock(IdentityProfileLookup.class);
    var roles = mock(IdentityRoleLookup.class);
    var passwordUpdater = mock(IdentityPasswordUpdater.class);
    var passwordChangeRequirement = mock(IdentityPasswordChangeRequirement.class);
    when(identities.findById(ACCOUNT_ID))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    ACCOUNT_ID,
                    new UsernameTranscoder().encodeUsername(EMAIL),
                    null,
                    null,
                    EMAIL)));
    when(roles.findAllByUserId(ACCOUNT_ID)).thenReturn(List.of("tenant-admin"));
    when(passwordChangeRequirement.requiresPasswordChange(ACCOUNT_ID)).thenReturn(true);
    var confirmation =
        new ExistingAccountSetupService(
            invites,
            admins,
            consultants,
            identities,
            roles,
            passwordChangeRequirement,
            verifier,
            passwordUpdater,
            transactions);
    ReflectionTestUtils.setField(confirmation, "multitenancyEnabled", true);
    assertThatThrownBy(() -> confirmation.confirm(OLD_TOKEN, "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            error ->
                assertThat(error.getReason()).isEqualTo(AccountInviteLinkException.Reason.EXPIRED));
    var actor = mock(AuthenticatedUser.class);
    when(actor.getUserId()).thenReturn("operator-1");
    when(actor.getUsername()).thenReturn("operator@example.org");
    var scope = mock(AdminScope.class);
    var urls = mock(InviteAcceptUrlBuilder.class);
    var newToken = new AtomicReference<String>();
    when(urls.buildAcceptUrl(eq(AccountInviteTargetRole.TENANT_ADMIN), any()))
        .thenAnswer(
            call -> {
              newToken.set(call.getArgument(1));
              return "https://app.example.org/setup";
            });
    var branding = mock(EmailBrandingResolver.class);
    var brand = new EmailBranding("Configured", null, "#124078", null, null);
    when(branding.resolve(42L)).thenReturn(brand);
    var brandValues = mock(TenantEmailBrandValues.class);
    when(brandValues.values(brand, 42L)).thenReturn(Map.of());
    var renderer = mock(OrisoEmailRenderer.class);
    when(renderer.render(eq("konto-einrichten"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    var mail = mock(InviteMailDispatchService.class);
    when(mail.sendRendered(eq(EMAIL), any(), any()))
        .thenReturn(new InviteMailSendReceipt(EMAIL, Instant.now()));
    var issuer =
        new ExistingAccountSetupIssuer(
            invites,
            mock(InviteEmailDeliveryRepository.class),
            admins,
            consultants,
            scope,
            actor,
            confirmation,
            verifier,
            urls,
            branding,
            brandValues,
            renderer,
            mail,
            transactions);
    ReflectionTestUtils.setField(issuer, "multitenancyEnabled", true);

    issuer.reissue(AccountInviteTargetRole.TENANT_ADMIN, ACCOUNT_ID);

    AccountInvite replaced = invites.findById(old.getId()).orElseThrow();
    AccountInvite fresh =
        new TransactionTemplate(transactions)
            .execute(status -> invites.findByActiveSetupIdentityKey(ACCOUNT_ID).orElseThrow());
    assertThat(replaced.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(replaced.getActiveSetupIdentityKey()).isNull();
    assertThat(replaced.getInitialPasswordVerifier()).isNull();
    assertThat(fresh.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(fresh.getInitialPasswordVerifier()).isEqualTo(privateHash);
    assertThat(newToken.get()).isNotBlank().isNotEqualTo(OLD_TOKEN);
    assertThat(fresh.getTokenHash()).isEqualTo(AccountInviteService.hash(newToken.get()));
    verify(scope).assertMay(AdminScope.Target.admin(ACCOUNT_ID));

    assertThatThrownBy(() -> confirmation.confirm(OLD_TOKEN, "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(AccountInviteLinkException.Reason.SUPERSEDED));
    confirmation.confirm(newToken.get(), "new-secret");
    verify(passwordUpdater, times(1)).updatePassword(ACCOUNT_ID, "new-secret");
    assertThatThrownBy(() -> confirmation.confirm(newToken.get(), "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(AccountInviteLinkException.Reason.CONSUMED));
    AccountInvite completed = invites.findById(fresh.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(completed.getInitialPasswordVerifier()).isNull();
    assertThat(completed.getActiveSetupIdentityKey()).isNull();
  }
}
