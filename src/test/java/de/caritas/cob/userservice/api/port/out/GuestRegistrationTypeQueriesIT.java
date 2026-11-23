package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** {@link GuestRegistrationTypeQueriesContract} on the H2 test database of every build. */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class GuestRegistrationTypeQueriesIT extends GuestRegistrationTypeQueriesContract {}
