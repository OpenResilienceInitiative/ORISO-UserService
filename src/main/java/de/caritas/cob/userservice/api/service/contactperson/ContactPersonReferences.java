package de.caritas.cob.userservice.api.service.contactperson;

import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a deleted admin from every contact person it was named as (ORISO-Admin#1534). A deleted
 * contact person takes its deputy along, since a deputy needs a contact person; a centre row left
 * empty goes, a Träger row stays while it still carries the general contact e-mail. Reads never
 * rely on this: an id of a missing admin counts as not set anyway.
 */
@Component
@RequiredArgsConstructor
public class ContactPersonReferences {

  private final @NonNull AgencyContactPersonRepository agencyContactPersonRepository;
  private final @NonNull TenantContactPersonRepository tenantContactPersonRepository;

  @Transactional
  public void forgetAdmin(String adminId) {
    if (adminId == null) {
      return;
    }
    agencyContactPersonRepository.clearContact(adminId);
    agencyContactPersonRepository.clearDeputy(adminId);
    agencyContactPersonRepository.deleteEmpty();
    tenantContactPersonRepository.clearContact(adminId);
    tenantContactPersonRepository.clearDeputy(adminId);
    tenantContactPersonRepository.deleteEmpty();
  }
}
