package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.Layer;
import de.caritas.cob.userservice.api.service.identity.observation.IdentityProbe;

/** Bounded provider identity facts for exact occupancy selectors. */
public interface IdentityOccupancyLookup {
  Layer read(IdentityProbe probe);
}
