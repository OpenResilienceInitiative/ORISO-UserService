package de.caritas.cob.userservice.api;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.consultant.ConsultantMatrixDisplayNamePublisher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PatchConsultantSaga {

  private final ConsultantRepository consultantRepository;

  private final UserServiceMapper userServiceMapper;

  private final ConsultantMatrixDisplayNamePublisher consultantMatrixDisplayNamePublisher;

  @Transactional
  public Map<String, Object> executeTransactional(
      Consultant patchedConsultant, Map<String, Object> patchMap) {
    Consultant savedConsultant = consultantRepository.save(patchedConsultant);
    if (userServiceMapper.displayNameOf(patchMap).isPresent()) {
      // #1541: like the PUT path, the Matrix profile follows -- after the commit only.
      consultantMatrixDisplayNamePublisher.publishAfterCommit(savedConsultant);
    }
    return userServiceMapper.mapOf(savedConsultant, patchMap);
  }
}
