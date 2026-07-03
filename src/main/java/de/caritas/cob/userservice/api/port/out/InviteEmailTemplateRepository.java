package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteEmailTemplateRepository extends JpaRepository<InviteEmailTemplate, Long> {

  List<InviteEmailTemplate> findByKindOrderByCreateDateDesc(InviteEmailTemplateKind kind);

  List<InviteEmailTemplate> findByKindAndActiveTrueOrderByCreateDateDesc(
      InviteEmailTemplateKind kind);
}
