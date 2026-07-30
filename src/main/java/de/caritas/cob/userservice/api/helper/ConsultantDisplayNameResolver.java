package de.caritas.cob.userservice.api.helper;

import de.caritas.cob.userservice.api.model.Consultant;
import org.springframework.stereotype.Component;

/**
 * Resolves the Matrix {@code displayname} a counsellor's account is provisioned with.
 *
 * <p>ADR-002 §2: the whole department are real, permanent members of a conversation room, and
 * silent members must be <em>pseudonymous</em> towards the advice seeker. The advice seeker is a
 * member of the same room, so their own client can read every member's display name straight from
 * {@code /joined_members} — filtering the participant list in the UI does not hide anything.
 *
 * <p>The invariant this class enforces: a counsellor's Matrix display name never reveals more
 * than the room's member list already does. The Matrix ID itself carries the (transcoded)
 * username, and the app already shows the client {@link Consultant#getDisplayName()} where one is
 * set — so those two are the only permitted sources. The real name is never used.
 */
@Component
public class ConsultantDisplayNameResolver {

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /**
   * @param consultant the counsellor whose Matrix account is being provisioned
   * @return the display name to register with Synapse, never the counsellor's real name
   */
  public String resolveMatrixDisplayName(Consultant consultant) {
    if (consultant == null) {
      return null;
    }

    var appDisplayName = consultant.getDisplayName();
    if (isUsable(appDisplayName)) {
      return appDisplayName;
    }

    // Falls back to what the Matrix ID already exposes, so provisioning adds no new information.
    return usernameTranscoder.decodeUsername(consultant.getUsername());
  }

  /**
   * An encoded value would render as noise rather than as a pseudonym, so it is treated as absent
   * and the username is used instead. Mirrors the check the notification service applies before
   * putting a counsellor's name in front of a client.
   */
  private boolean isUsable(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return !value.startsWith("enc.") && !value.matches("^[A-Za-z0-9+/=]{25,}$");
  }
}
