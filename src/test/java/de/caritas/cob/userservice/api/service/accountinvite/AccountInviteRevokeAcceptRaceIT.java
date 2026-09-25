package de.caritas.cob.userservice.api.service.accountinvite;

import org.springframework.test.context.TestPropertySource;

/** {@link RevokeAcceptRaceContract} on H2, part of every integration run. */
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      // Revoke waits on the accept's row lock; H2's default of 2 s is too close to the test's.
      "spring.datasource.url=jdbc:h2:mem:revoke-accept-race-${random.uuid};DB_CLOSE_DELAY=-1;"
          + "DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;NON_KEYWORDS=USER,VALUE,DAY;LOCK_TIMEOUT=10000"
    })
class AccountInviteRevokeAcceptRaceIT extends RevokeAcceptRaceContract {}
