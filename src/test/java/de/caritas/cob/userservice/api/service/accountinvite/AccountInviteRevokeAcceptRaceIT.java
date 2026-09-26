package de.caritas.cob.userservice.api.service.accountinvite;

import org.springframework.test.context.TestPropertySource;

/** {@link RevokeAcceptRaceContract} on H2, part of every integration run. */
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      // Longer than every intended wait in the contract, shorter than its "held too long" case.
      "spring.datasource.url=jdbc:h2:mem:revoke-accept-race-${random.uuid};DB_CLOSE_DELAY=-1;"
          + "DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;NON_KEYWORDS=USER,VALUE,DAY;LOCK_TIMEOUT=3000"
    })
class AccountInviteRevokeAcceptRaceIT extends RevokeAcceptRaceContract {

  @Override
  String lockWaitersQuery() {
    return "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL";
  }
}
