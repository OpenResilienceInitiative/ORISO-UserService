package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.PreparedCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import java.net.URI;
import java.util.Objects;
import org.springframework.web.client.RestTemplate;

/** Actual native owned-read is the only constructor of the disabled pending-account handoff. */
public final class KeycloakProtectedAccounts {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final ProtectedAccountCodec codec;
  private final URI authServer;
  private final String realmName;

  public KeycloakProtectedAccounts(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedAccountCodec codec,
      String authServer,
      String realmName) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    if (realmName == null || realmName.isBlank() || !realmName.equals(codec.realmName()))
      throw invalid();
    this.realmName = realmName;
    try {
      URI server = URI.create(authServer);
      if (!("http".equalsIgnoreCase(server.getScheme())
              || "https".equalsIgnoreCase(server.getScheme()))
          || server.getHost() == null
          || server.getRawUserInfo() != null
          || server.getRawQuery() != null
          || server.getRawFragment() != null) throw invalid();
      this.authServer = server;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public VerifiedCreated create(PreparedCreation prepared) {
    return null;
  }

  public PendingOwnedAccount ownedRead(AcceptanceClaim claim, VerifiedCreated created) {
    return null;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid protected account transport binding");
  }

  /**
   * Not constructible from an account id, creation receipt or unsigned response supplied by a
   * caller.
   */
  public static final class PendingOwnedAccount {
    private final AcceptanceClaim claim;
    private final VerifiedCreated created;
    private final String realmName;

    private PendingOwnedAccount(AcceptanceClaim claim, VerifiedCreated created, String realmName) {
      this.claim = claim;
      this.created = created;
      this.realmName = realmName;
    }

    public AcceptanceClaim claim() {
      return claim;
    }

    public VerifiedCreated created() {
      return created;
    }

    public String realmName() {
      return realmName;
    }

    public String realmId() {
      return claim.frozen().issuerRealm();
    }

    @Override
    public String toString() {
      return "ProtectedPendingOwnedAccount[redacted]";
    }
  }

  public static final class RemoteFailure extends RuntimeException {
    private final int status;

    public RemoteFailure(int status) {
      super("Protected account command unavailable (status=" + status + ")");
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
