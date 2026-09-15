package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantPicture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Access only through the picture store, after locked consultant ownership validation. */
public interface ConsultantPictureRepository extends JpaRepository<ConsultantPicture, String> {
  @Modifying
  @Query("delete from ConsultantPicture p where p.consultantId = :consultantId")
  void removeByConsultantId(String consultantId);
}
