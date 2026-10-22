package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaimedPasswordIntentCodecTest {
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  private final String account = "00000000-0000-4000-8000-000000000001";
  private final String claim = "00000000-0000-4000-8000-000000000002";
  private final String operation = "00000000-0000-4000-8000-000000000003";
  private ObjectNode wrapper() {
    var value = codec.read("{}", ObjectNode.class);
    var body = value.putObject("body");
    body.put("operationId", operation); body.put("expectedState", "a".repeat(64));
    var target = body.putObject("target"); target.put("accountId", account); target.put("tenantId", "1");
    var origin = target.putObject("origin"); origin.put("kind", "PASSWORD_RESET"); origin.put("workflowId", claim);
    for (String name : new String[]{"humanSubject", "humanIssuer", "humanClient", "humanAuthority"}) origin.putNull(name);
    var workflow = body.putObject("workflow"); workflow.put("v", 1); workflow.put("kind", "PASSWORD_RESET");
    workflow.put("claimId", claim); workflow.put("claimVersion", 1); workflow.put("authorityValidUntil", 1800000000L);
    workflow.put("expectedUsername", "enc.original"); workflow.put("expectedEmailDigest", "b".repeat(64));
    workflow.put("profileKind", "ORDINARY"); workflow.put("profileId", "ordinary-consultant-v1"); workflow.put("profileVersion", 1);
    body.putObject("change").put("password", "Unstored-public-fixture!42").put("temporary", false);
    value.putObject("localState").put("primaryKind", "CONSULTANT").put("primaryVersion", "c".repeat(64)).putNull("consultantVersion");
    return value;
  }
  private ObjectNode metadata(ObjectNode wrapper) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1); value.put("realmId", "realm-367"); value.put("operationId", operation);
    value.put("command", "account.password-reset"); value.put("method", "POST");
    value.put("route", "oriso-account-maintenance/v1/accounts/password-reset");
    value.put("accountId", account); value.put("tenantId", 1); value.put("ownerClient", "ORISO_ACCOUNT_ADMIN");
    value.put("ownerSubject", "00000000-0000-4000-8000-000000000004");
    value.put("originDigest", codec.digest("maintenance-origin", wrapper.path("body").path("target").path("origin")));
    value.put("claimId", claim); value.put("claimVersion", 1); value.put("workflowKind", "PASSWORD_RESET");
    value.put("workflowDigest", codec.digest("password-reset-workflow/v1", wrapper.path("body").path("workflow")));
    value.put("requestDigest", "d".repeat(64)); value.put("expectedState", "a".repeat(64));
    value.put("authorityValidUntil", 1800000000L); value.put("representation", "FROZEN_CLAIMED_PASSWORD_HTTP_V1");
    return value;
  }
  private byte[] bytes(JsonNode wrapper) { return codec.canonical(wrapper).getBytes(StandardCharsets.UTF_8); }

  @Test void fixedCausalSchemaEncryptsCompleteChosenPasswordAndRoundTripsOnlyItsMetadata() {
    var wrapper = wrapper(); var metadata = metadata(wrapper);
    assertThatCode(() -> codec.sealClaimedPasswordIntent(metadata, bytes(wrapper))).doesNotThrowAnyException();
    String envelope = codec.sealClaimedPasswordIntent(metadata, bytes(wrapper));
    assertThat(envelope).doesNotContain("Unstored-public-fixture", "enc.original", "ordinary-consultant");
    assertThat(codec.openClaimedPasswordIntent(metadata, envelope)).isEqualTo(bytes(wrapper));
    var otherClaim = metadata.deepCopy().put("claimId", account);
    assertThatThrownBy(() -> codec.openClaimedPasswordIntent(otherClaim, envelope)).isInstanceOf(IllegalArgumentException.class);
    var otherTenant = metadata.deepCopy().put("tenantId", 2);
    assertThatThrownBy(() -> codec.openClaimedPasswordIntent(otherTenant, envelope)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void distinctSetupSchemaAcceptsOnlyTheExactOriginalClaimAndWorkflow() {
    var wrapper = wrapper(); ((ObjectNode)wrapper.path("body").path("workflow")).put("kind", "EXISTING_SETUP");
    var metadata = metadata(wrapper).put("workflowKind", "EXISTING_SETUP");
    assertThatCode(() -> codec.sealClaimedPasswordIntent(metadata, bytes(wrapper))).doesNotThrowAnyException();
    String envelope = codec.sealClaimedPasswordIntent(metadata, bytes(wrapper));
    var changed = metadata.deepCopy().put("claimVersion", 2);
    assertThatThrownBy(() -> codec.openClaimedPasswordIntent(changed, envelope)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.openSelfPasswordIntent(metadata, envelope)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void aliasesUnknownFieldsWrongRoutesAndPlaintextRepresentationsRefuse() {
    var wrapper = wrapper(); var metadata = metadata(wrapper);
    metadata.put("authorityUntil", 1800000000L);
    assertThatThrownBy(() -> codec.sealClaimedPasswordIntent(metadata, bytes(wrapper))).isInstanceOf(IllegalArgumentException.class);
    metadata.remove("authorityUntil"); metadata.put("route", "arbitrary-route");
    assertThatThrownBy(() -> codec.sealClaimedPasswordIntent(metadata, bytes(wrapper))).isInstanceOf(IllegalArgumentException.class);
    var duplicate = wrapper.deepCopy(); ((ObjectNode)duplicate.path("body").path("change")).put("currentPassword", "unrelated");
    assertThatThrownBy(() -> codec.sealClaimedPasswordIntent(metadata(wrapper), bytes(duplicate))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.openClaimedPasswordIntent(metadata(wrapper), codec.canonical(Map.of("v", 1, "nonce", "plain", "ciphertext", "password")))).isInstanceOf(IllegalArgumentException.class);
  }
}
