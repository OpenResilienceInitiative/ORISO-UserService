package de.caritas.cob.userservice.api.port.out;

import java.util.List;

/** Focused outbound identity realm-role read contract. */
public interface IdentityRoleLookup {

  List<String> findAllByUserId(String userId);
}
