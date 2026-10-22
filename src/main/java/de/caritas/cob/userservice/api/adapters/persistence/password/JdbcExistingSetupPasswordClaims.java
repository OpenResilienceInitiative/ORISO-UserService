package de.caritas.cob.userservice.api.adapters.persistence.password;

import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

/** Same-database setup claim journal. No provider, credential, token-store or crypto operations. */
public final class JdbcExistingSetupPasswordClaims implements ExistingSetupPasswordClaims {
  public JdbcExistingSetupPasswordClaims(JdbcTemplate jdbc, Clock clock) {}

  @Override
  public Claim claimWithinOwner(Binding binding, String completeKeyedRequestDigest) {
    throw unavailable();
  }

  @Override
  public void requireCurrentWithinOwner(Claim claim) {
    throw unavailable();
  }

  @Override
  public void markUnknownWithinOwner(Claim claim) {
    throw unavailable();
  }

  @Override
  public void rejectWithinOwner(Claim claim) {
    throw unavailable();
  }

  @Override
  public void acknowledgeWithinOwner(Claim claim) {
    throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Existing setup password claim unavailable");
  }
}
