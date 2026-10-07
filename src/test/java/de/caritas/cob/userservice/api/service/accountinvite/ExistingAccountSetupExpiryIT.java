package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Real expiry, issuer, held claim and signed password completion against committed local rows. */
@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@ActiveProfiles({"testing", "verified-request-caller"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExistingAccountSetupExpiryIT {

  private static final String ACCOUNT_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000042";
  private static final String OPERATOR_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000043";
  private static final String EMAIL = "admin@example.org";
  private static final String OLD_TOKEN = "expired-setup-token";
  private static final long TENANT = 42L;

  @Autowired private AccountInviteRepository invites;
  @Autowired private AdminRepository admins;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InitialPasswordVerifier verifier;
  @Autowired private ExistingAccountSetupService confirmation;
  @Autowired private ExistingAccountSetupIssuer issuer;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment environment;
  @Autowired private ObjectMapper mapper;

  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate identityHttp;

  @MockitoBean(name = "restTemplate")
  private RestTemplate taskAuthHttp;

  @MockitoBean private JwtDecoder taskDecoder;
  @MockitoBean private InviteAcceptUrlBuilder urls;
  @MockitoBean private EmailBrandingResolver branding;
  @MockitoBean private TenantEmailBrandValues brandValues;
  @MockitoBean private OrisoEmailRenderer renderer;
  @MockitoBean private InviteMailDispatchService mail;

  private BoundedIdentityHttpFixtures.Provider provider;
  private final AtomicReference<String> newToken = new AtomicReference<>();
  private SecurityContext previousSecurity;
  private RequestAttributes previousRequest;
  private ServletRequestAttributes operatorRequest;

  @BeforeEach
  void givenActualAccountAndAuthorizedOperator() {
    Tenants.in(
        TENANT,
        () -> {
          admins.saveAndFlush(admin(ACCOUNT_ID, EMAIL));
          admins.saveAndFlush(admin(OPERATOR_ID, "operator@example.org"));
        });
    BoundedIdentityHttpFixtures.givenTaskGrants(taskAuthHttp, taskDecoder, taskIdentities);
    provider =
        BoundedIdentityHttpFixtures.givenProvider(
            identityHttp,
            taskIdentities,
            environment,
            mapper,
            body -> java.util.UUID.randomUUID().toString(),
            id -> {},
            command -> {});
    provider.seed(
        new KeycloakTaskCommands.AccountProjection(
            ACCOUNT_ID,
            EMAIL,
            EMAIL,
            "A",
            "D",
            TENANT,
            "de",
            true,
            false,
            List.of("tenant-admin"),
            true));

    var operator = Tenants.in(TENANT, () -> admins.findById(OPERATOR_ID).orElseThrow());
    var token =
        Jwt.withTokenValue("synthetic-human-setup-reissue")
            .header("alg", "RS256")
            .subject(operator.getId())
            .claim("azp", "app")
            .claim("preferred_username", operator.getUsername())
            .claim("tenantId", Long.toString(TENANT))
            .claim("realm_access", Map.of("roles", List.of("tenant-admin")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new JwtAuthenticationToken(
            token, List.of(new SimpleGrantedAuthority(AuthorityValue.TENANT_ADMIN)));
    previousSecurity = SecurityContextHolder.getContext();
    previousRequest = RequestContextHolder.getRequestAttributes();
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(authentication);
    SecurityContextHolder.setContext(security);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    operatorRequest = new ServletRequestAttributes(request);
    RequestContextHolder.setRequestAttributes(operatorRequest);

    newToken.set(null);
    when(urls.buildAcceptUrl(eq(AccountInviteTargetRole.TENANT_ADMIN), any()))
        .thenAnswer(
            call -> {
              newToken.set(call.getArgument(1));
              return "https://app.example.org/setup";
            });
    var brand = new EmailBranding("Configured", null, "#124078", null, null);
    when(branding.resolve(TENANT)).thenReturn(brand);
    when(brandValues.values(brand, TENANT)).thenReturn(Map.of());
    when(renderer.render(eq("konto-einrichten"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    when(mail.sendRendered(eq(EMAIL), any(), any()))
        .thenReturn(new InviteMailSendReceipt(EMAIL, Instant.now()));
  }

  @AfterEach
  void cleanUp() {
    restoreHumanContext();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              // These two fixture identities own every row removed here.
              jdbc.update(
                  "DELETE FROM invite_email_delivery WHERE account_invite_id IN (SELECT id FROM"
                      + " account_invite WHERE purpose = 'EXISTING_ACCOUNT_SETUP' AND"
                      + " provisioned_user_id = ?)",
                  ACCOUNT_ID);
              jdbc.update(
                  "DELETE FROM account_invite WHERE purpose = 'EXISTING_ACCOUNT_SETUP' AND"
                      + " provisioned_user_id = ?",
                  ACCOUNT_ID);
              jdbc.update("DELETE FROM admin WHERE admin_id IN (?, ?)", ACCOUNT_ID, OPERATOR_ID);
            });
  }

  @Test
  void expiredSetupCanBeReissuedAndOnlyItsNewLinkCanCompleteOnce() {
    String privateHash = verifier.encode("initial-secret");
    AccountInvite old = seedSetup(privateHash, LocalDateTime.now().minusDays(1));
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                assertThat(
                        invites.expireElapsedExistingAccountSetup(
                            AccountInvitePurpose.EXISTING_ACCOUNT_SETUP,
                            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT),
                            AccountInviteStatus.EXPIRED,
                            AccountInviteProvisioningStatus.IN_PROGRESS,
                            LocalDateTime.now()))
                    .isEqualTo(1));
    assertThat(invites.findById(old.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.EXPIRED);
    assertLinkReason(OLD_TOKEN, AccountInviteLinkException.Reason.EXPIRED);

    Tenants.in(TENANT, () -> issuer.reissue(AccountInviteTargetRole.TENANT_ADMIN, ACCOUNT_ID));

    AccountInvite replaced = invites.findById(old.getId()).orElseThrow();
    AccountInvite fresh = activeSetup();
    assertThat(replaced.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(replaced.getActiveSetupIdentityKey()).isNull();
    assertThat(replaced.getInitialPasswordVerifier()).isNull();
    assertThat(fresh.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(fresh.getTenantId()).isEqualTo(TENANT);
    assertThat(fresh.getInitialPasswordVerifier()).isEqualTo(privateHash);
    assertThat(newToken.get()).isNotBlank().isNotEqualTo(OLD_TOKEN);
    assertThat(fresh.getTokenHash()).isEqualTo(AccountInviteService.hash(newToken.get()));

    // Public completion proves its held link, independently of the reissuing administrator.
    restoreHumanContext();
    assertLinkReason(OLD_TOKEN, AccountInviteLinkException.Reason.SUPERSEDED);
    assertThatThrownBy(() -> confirmation.confirm(newToken.get(), "initial-secret"))
        .isInstanceOf(BadRequestException.class);
    assertThat(passwordCommands()).isEmpty();
    confirmation.confirm(newToken.get(), "new-secret");
    assertThat(passwordCommands())
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.target()).isEqualTo(ACCOUNT_ID);
              assertThat(command.body().get("password")).isEqualTo("new-secret");
              assertThat(command.body().get("passwordTemporary")).isEqualTo(false);
            });
    assertThat(provider.projections().get(ACCOUNT_ID).passwordChangeRequired()).isFalse();
    assertLinkReason(newToken.get(), AccountInviteLinkException.Reason.CONSUMED);
    assertThat(passwordCommands()).hasSize(1);
    AccountInvite completed = invites.findById(fresh.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(completed.getInitialPasswordVerifier()).isNull();
    assertThat(completed.getActiveSetupIdentityKey()).isNull();
  }

  @Test
  void heldSetupIsRevokedIfTheActualSavedIdentityChangesBeforePasswordConfirmation() {
    seedSetup(verifier.encode("initial-secret"), LocalDateTime.now().minusDays(1));
    Tenants.in(TENANT, () -> issuer.reissue(AccountInviteTargetRole.TENANT_ADMIN, ACCOUNT_ID));
    var fresh = activeSetup();
    Tenants.in(
        TENANT,
        () -> {
          var changed = admins.findById(ACCOUNT_ID).orElseThrow();
          changed.setEmail("changed@example.org");
          admins.saveAndFlush(changed);
        });
    restoreHumanContext();

    assertLinkReason(newToken.get(), AccountInviteLinkException.Reason.REVOKED);

    assertThat(invites.findById(fresh.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(passwordCommands()).isEmpty();
    assertThat(provider.projections().get(ACCOUNT_ID).passwordChangeRequired()).isTrue();
  }

  private static Admin admin(String id, String email) {
    return Admin.builder()
        .id(id)
        .type(Admin.AdminType.TENANT)
        .tenantId(TENANT)
        .firstName("A")
        .lastName("D")
        .username(email)
        .email(email)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private AccountInvite seedSetup(String privateHash, LocalDateTime expiresAt) {
    return Tenants.in(
        TENANT,
        () ->
            invites.saveAndFlush(
                AccountInvite.builder()
                    .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                    .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
                    .tenantId(TENANT)
                    .recipientEmail(EMAIL)
                    .setupBoundUsername(EMAIL)
                    .provisionedUserId(ACCOUNT_ID)
                    .activeSetupIdentityKey(ACCOUNT_ID)
                    .initialPasswordVerifier(privateHash)
                    .tokenHash(AccountInviteService.hash(OLD_TOKEN))
                    .status(AccountInviteStatus.EMAIL_SENT)
                    .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
                    .expiresAt(expiresAt)
                    .createDate(LocalDateTime.now().minusDays(2))
                    .build()));
  }

  private AccountInvite activeSetup() {
    return Tenants.in(
        TENANT,
        () ->
            new TransactionTemplate(transactions)
                .execute(status -> invites.findByActiveSetupIdentityKey(ACCOUNT_ID).orElseThrow()));
  }

  private List<BoundedIdentityHttpFixtures.Command> passwordCommands() {
    return provider.commands().stream()
        .filter(command -> command.operation().equals("account.password"))
        .toList();
  }

  private void assertLinkReason(String token, AccountInviteLinkException.Reason reason) {
    assertThatThrownBy(() -> confirmation.confirm(token, "new-secret"))
        .isInstanceOfSatisfying(
            AccountInviteLinkException.class,
            error -> assertThat(error.getReason()).isEqualTo(reason));
  }

  private void restoreHumanContext() {
    if (operatorRequest == null) return;
    operatorRequest.requestCompleted();
    RequestContextHolder.setRequestAttributes(previousRequest);
    SecurityContextHolder.setContext(previousSecurity);
    operatorRequest = null;
  }
}
