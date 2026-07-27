package de.caritas.cob.userservice.api;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatchConsultantSaga {

  private final ConsultantRepository consultantRepository;

  private final UserServiceMapper userServiceMapper;

  private final AppointmentService appointmentService;

  @Transactional
  public Map<String, Object> executeTransactional(
      Consultant patchedConsultant, Map<String, Object> patchMap) {
    Consultant savedConsultant = saveConsultant(patchedConsultant);
    userServiceMapper
        .displayNameOf(patchMap)
        .ifPresent(
            displayName ->
                patchConsultantInAppointmentServiceOrRollback(savedConsultant, displayName));
    return userServiceMapper.mapOf(savedConsultant, patchMap);
  }

  private void patchConsultantInAppointmentServiceOrRollback(
      Consultant savedConsultant, String displayName) {

    try {
      appointmentService.patchConsultant(savedConsultant.getId(), displayName);
    } catch (Exception e) {
      log.error(
          "Error while patching consultant in appointment service. Will rollback patchConsultantSaga.",
          e);
      // rollback on MariaDB will be handled automatically by spring due to @Transactional
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .completedTransactionalOperations(
                  Lists.newArrayList(TransactionalStep.SAVE_CONSULTANT_IN_MARIADB))
              .name("patchConsultant")
              .failedStep(TransactionalStep.PATCH_APPOINTMENT_SERVICE_CONSULTANT)
              .build());
    }
  }

  private Consultant saveConsultant(Consultant patchedConsultant) {
    return consultantRepository.save(patchedConsultant);
  }
}
