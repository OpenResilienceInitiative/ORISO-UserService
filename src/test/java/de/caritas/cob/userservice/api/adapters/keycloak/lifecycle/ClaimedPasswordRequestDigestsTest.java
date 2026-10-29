package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.auth.PasswordResetBinding;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimedPasswordRequestDigestsTest {
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  private final ClaimedPasswordRequestDigests digests = new ClaimedPasswordRequestDigests(codec);
  private final String account = "00000000-0000-4000-8000-000000000001";

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"opaque\nvalue", "\t", "   "})
  void actualResetAndSetupRequestBindingsPreserveOpaqueSecrets(String secret) {
    var reset =
        new PasswordResetBinding(
            UUID.randomUUID(),
            account,
            1L,
            "enc.original",
            "CONSULTANT",
            "v1",
            "a".repeat(64),
            Instant.parse("2026-10-23T12:00:00Z"));
    var setup =
        new ExistingSetupPasswordClaims.Binding(
            1,
            account,
            "b".repeat(64),
            LocalDateTime.parse("2026-10-23T12:00:00"),
            AccountInviteTargetRole.COUNSELLOR,
            1L,
            "enc.original",
            "original@example.org");
    assertThat(codec.passwordResetRequestDigest(reset, secret))
        .matches("[a-f0-9]{64}")
        .isNotEqualTo(codec.passwordResetRequestDigest(reset, secret + " "));
    assertThat(codec.existingSetupPasswordRequestDigest(setup, secret))
        .matches("[a-f0-9]{64}")
        .isNotEqualTo(codec.existingSetupPasswordRequestDigest(setup, secret + " "));
    for (String invalid : new String[] {null, "", "x".repeat(4097)}) {
      assertThatThrownBy(() -> codec.passwordResetRequestDigest(reset, invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> codec.existingSetupPasswordRequestDigest(setup, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void recipientMatchesExactNativeStringDomainWithoutNormalization() {
    assertThatCode(() -> codec.passwordResetRecipientDigest("Original+one@Example.org"))
        .doesNotThrowAnyException();
    assertThat(digests.recipient("Original+one@Example.org"))
        .isEqualTo(codec.digest("maintenance-workflow-email/v1", "Original+one@Example.org"))
        .isNotEqualTo(digests.recipient("original+one@example.org"));
  }

  @Test
  void resetBindsOriginalIssuanceExpiryTargetAndCompletePassword() {
    var id = UUID.randomUUID();
    var expiry = Instant.parse("2026-10-23T12:00:00Z");
    var b =
        new PasswordResetBinding(
            id, account, 1L, "enc.original", "CONSULTANT", "v1", "a".repeat(64), expiry);
    assertThatCode(() -> codec.passwordResetRequestDigest(b, "Password1!"))
        .doesNotThrowAnyException();
    String expected = digests.reset(b, "Password1!");
    assertThat(expected).matches("[a-f0-9]{64}");
    assertThat(digests.reset(b, "Password2!")).isNotEqualTo(expected);
    assertThat(
            digests.reset(
                new PasswordResetBinding(
                    UUID.randomUUID(),
                    account,
                    1L,
                    b.username(),
                    b.accountKind(),
                    b.accountVersion(),
                    b.recipientDigest(),
                    expiry),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.reset(
                new PasswordResetBinding(
                    id,
                    account,
                    2L,
                    b.username(),
                    b.accountKind(),
                    b.accountVersion(),
                    b.recipientDigest(),
                    expiry),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.reset(
                new PasswordResetBinding(
                    id,
                    account,
                    1L,
                    b.username(),
                    b.accountKind(),
                    b.accountVersion(),
                    b.recipientDigest(),
                    expiry.plusSeconds(1)),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.reset(
                new PasswordResetBinding(
                    id,
                    account,
                    1L,
                    b.username(),
                    b.accountKind(),
                    "v2",
                    b.recipientDigest(),
                    expiry),
                "Password1!"))
        .isNotEqualTo(expected);
  }

  @Test
  void setupBindsExactInvitationTokenRoleAndMailbox() {
    var expiry = LocalDateTime.parse("2026-10-23T12:00:00");
    var b =
        new ExistingSetupPasswordClaims.Binding(
            1,
            account,
            "b".repeat(64),
            expiry,
            AccountInviteTargetRole.COUNSELLOR,
            1L,
            "enc.original",
            "original@example.org");
    assertThatCode(() -> codec.existingSetupPasswordRequestDigest(b, "Password1!"))
        .doesNotThrowAnyException();
    String expected = digests.existingSetup(b, "Password1!");
    assertThat(expected).matches("[a-f0-9]{64}");
    assertThat(digests.existingSetup(b, "Password2!")).isNotEqualTo(expected);
    assertThat(
            digests.existingSetup(
                new ExistingSetupPasswordClaims.Binding(
                    2, account, b.tokenHash(), expiry, b.role(), 1L, b.username(), b.email()),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.existingSetup(
                new ExistingSetupPasswordClaims.Binding(
                    1, account, "c".repeat(64), expiry, b.role(), 1L, b.username(), b.email()),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.existingSetup(
                new ExistingSetupPasswordClaims.Binding(
                    1,
                    account,
                    b.tokenHash(),
                    expiry,
                    AccountInviteTargetRole.TENANT_ADMIN,
                    1L,
                    b.username(),
                    b.email()),
                "Password1!"))
        .isNotEqualTo(expected);
    assertThat(
            digests.existingSetup(
                new ExistingSetupPasswordClaims.Binding(
                    1,
                    account,
                    b.tokenHash(),
                    expiry,
                    b.role(),
                    1L,
                    b.username(),
                    "other@example.org"),
                "Password1!"))
        .isNotEqualTo(expected);
  }

  @Test
  void malformedBindingsAndOversizedPasswordRefuse() {
    assertThatThrownBy(() -> digests.reset(null, "Password1!"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> digests.existingSetup(null, "Password1!"))
        .isInstanceOf(IllegalArgumentException.class);
    var b =
        new PasswordResetBinding(
            UUID.randomUUID(),
            account,
            1L,
            "enc.original",
            "CONSULTANT",
            "v1",
            "a".repeat(64),
            Instant.now());
    assertThatThrownBy(() -> digests.reset(b, "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> digests.reset(b, "a".repeat(4097)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode localRows() {
    var rows = codec.read("{}", com.fasterxml.jackson.databind.node.ObjectNode.class);
    rows.putNull("user");
    rows.putNull("admin");
    rows.putObject("consultant")
        .put("accountId", account)
        .put("tenantId", 1)
        .put("username", "enc.original")
        .put("email", "Original@Example.org")
        .put("createDate", "2026-10-22T12:00")
        .put("updateDate", "2026-10-22T13:00")
        .putNull("deleteDate")
        .put("lifecycle", "ACTIVE")
        .put("managedPaused", false)
        .put("passwordChangeRequired", true);
    return rows;
  }

  @Test
  void localVersionBindsTheCompleteActualSqlSnapshotWithoutExposingMailbox() {
    var rows = localRows();
    assertThatCode(() -> codec.claimedPasswordLocalAccountVersion(rows)).doesNotThrowAnyException();
    String version = codec.claimedPasswordLocalAccountVersion(rows);
    assertThat(version).matches("[a-f0-9]{64}");
    var changed = rows.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("consultant"))
        .put("updateDate", "2026-10-22T13:00:01");
    assertThat(codec.claimedPasswordLocalAccountVersion(changed)).isNotEqualTo(version);
    changed = rows.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("consultant"))
        .put("passwordChangeRequired", false);
    assertThat(codec.claimedPasswordLocalAccountVersion(changed)).isNotEqualTo(version);
  }

  @Test
  void localVersionRejectsUnknownRowKindsAliasesAndCrossAccountSecondaryRows() {
    var rows = localRows();
    rows.putNull("unreviewed");
    assertThatThrownBy(() -> codec.claimedPasswordLocalAccountVersion(rows))
        .isInstanceOf(IllegalArgumentException.class);
    var other = localRows();
    other
        .putObject("admin")
        .put("accountId", "00000000-0000-4000-8000-000000000009")
        .put("tenantId", 1)
        .put("username", "admin")
        .put("email", "Original@Example.org")
        .put("createDate", "2026-10-22T12:00")
        .put("updateDate", "2026-10-22T13:00")
        .put("type", "TENANT")
        .put("managedPaused", false);
    assertThatThrownBy(() -> codec.claimedPasswordLocalAccountVersion(other))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
