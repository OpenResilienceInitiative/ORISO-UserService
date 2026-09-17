package de.caritas.cob.userservice.api.picture;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantPicture;
import de.caritas.cob.userservice.api.port.out.ConsultantPictureRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsultantPictureStore {
  private final ConsultantRepository consultants;
  private final ConsultantPictureRepository pictures;
  private final ConsultantPictureAccess access;
  private final EntityManager entityManager;

  /** Also used by soft deletion; caller must hold the enclosing lifecycle transaction. */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Consultant lockActiveConsultant(String id) {
    // Preserve pending aggregate writes before detaching, as the original query's AUTO flush did.
    entityManager.flush();
    // Discard only the pre-scan owner snapshot. Hibernate may optimize refresh of an already
    // locked entity to a non-locking SELECT, which can read MariaDB's repeatable-read snapshot.
    // Reloading a detached owner with SELECT FOR UPDATE obtains current fields and the lock.
    entityManager.detach(entityManager.getReference(Consultant.class, id));
    var consultant =
        consultants
            .findPictureOwnerForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));
    if (consultant.getDeleteDate() != null) throw new NotFoundException("Consultant not found");
    return consultant;
  }

  @Transactional
  public void replace(String id, byte[] bytes, String contentType) {
    var consultant = lockActiveConsultant(id);
    access.checkTarget(consultant, true);
    pictures.save(new ConsultantPicture(consultant.getId(), bytes, contentType));
  }

  @Transactional
  public ConsultantPicture read(String id) {
    var consultant = lockActiveConsultant(id);
    access.checkTarget(consultant, false);
    return pictures
        .findById(consultant.getId())
        .orElseThrow(() -> new NotFoundException("Picture not found"));
  }

  @Transactional
  public void remove(String id) {
    var consultant = lockActiveConsultant(id);
    access.checkTarget(consultant, true);
    pictures.removeByConsultantId(consultant.getId());
  }

  /** Issue #1049: the stored publish decision, for the administrative form. */
  @Transactional(readOnly = true)
  public boolean readInternalOnly(String id) {
    var consultant =
        consultants
            .findByIdAndDeleteDateIsNull(id)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));
    access.checkTarget(consultant, false);
    return picture(consultant.getId(), "Picture not found").isInternalOnly();
  }

  /**
   * Publish or withdraw an existing picture. Withdrawal takes effect on the next read because the
   * published route never caches and always re-reads this flag.
   */
  @Transactional
  public void writeInternalOnly(String id, boolean internalOnly) {
    var consultant = lockActiveConsultant(id);
    access.checkTarget(consultant, true);
    picture(consultant.getId(), "Picture not found").setInternalOnly(internalOnly);
  }

  /**
   * Advice-seeker facing read. Unpublished pictures are indistinguishable from absent ones, and no
   * row lock is taken because this path never writes.
   */
  @Transactional(readOnly = true)
  public ConsultantPicture readPublished(String id) {
    var consultant =
        consultants
            .findByIdAndDeleteDateIsNull(id)
            .orElseThrow(() -> new NotFoundException("Picture not found"));
    access.checkPublishedReaderTarget(consultant);
    var picture = picture(consultant.getId(), "Picture not found");
    if (picture.isInternalOnly()) throw new NotFoundException("Picture not found");
    return picture;
  }

  private ConsultantPicture picture(String id, String message) {
    return pictures.findById(id).orElseThrow(() -> new NotFoundException(message));
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void removeForConsultantDeletion(String id) {
    pictures.removeByConsultantId(id);
  }
}
