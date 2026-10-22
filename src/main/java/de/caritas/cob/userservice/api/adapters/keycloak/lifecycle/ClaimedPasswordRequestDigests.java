package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.service.auth.PasswordResetBinding;

/** Default-closed scaffold for fixed typed complete-request and original-mail digests. */
final class ClaimedPasswordRequestDigests {
  ClaimedPasswordRequestDigests(AccountLifecycleCodec codec) {}
  String existingSetup(ExistingSetupPasswordClaims.Binding binding, String password) { throw invalid(); }
  String reset(PasswordResetBinding binding, String password) { throw invalid(); }
  String recipient(String email) { throw invalid(); }
  String localAccounts(JsonNode rows) { throw invalid(); }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("Unavailable claimed password digest"); }
}
