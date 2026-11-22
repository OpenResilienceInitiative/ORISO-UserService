package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The #1700 journey changeset and the #1712 first-room changesets both have to stay applied. */
class FirstEnquiryChangelogOrderTest {

  @Test
  void journeyStorageStaysAheadOfTheFirstRoomChangesets() throws Exception {
    String master;
    try (var stream = getClass().getResourceAsStream("/db/changelog/userservice-master.xml")) {
      assertThat(stream).isNotNull();
      master = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    int journey = master.indexOf("20261119_journey_storage_1689/changeSet.xml");
    int binding = master.indexOf("20261119_agency_enquiry_binding_index_1692/changeSet.xml");
    int attempt = master.indexOf("20261120_agency_enquiry_room_attempt_1692/changeSet.xml");
    int roomScope =
        master.indexOf("20261121_agency_enquiry_revocation_room_scope_1692/changeSet.xml");

    assertThat(journey).isNotNegative();
    assertThat(binding).isGreaterThan(journey);
    assertThat(attempt).isGreaterThan(binding);
    assertThat(roomScope).isGreaterThan(attempt);
  }
}
