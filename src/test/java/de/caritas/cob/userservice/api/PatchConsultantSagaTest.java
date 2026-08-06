package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Maps;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchConsultantSagaTest {

  private static final String CHANGED_DISPLAY_NAME = "new displayName";
  private static final String CONSULTANT_ID = "consultantId";
  private static final String MATRIX_USER_ID = "@consultant:matrix.example";
  @InjectMocks PatchConsultantSaga patchConsultantSaga;

  @Mock ConsultantRepository consultantRepository;

  @Mock UserServiceMapper userServiceMapper;

  @Mock AppointmentService appointmentService;

  @Test
  void executeTransactionalOrRollback_Should_SaveConsultantAndUpdateAppointmentService() {
    // given
    Map<String, Object> patchMap = givenPatchMapWithDisplayName();
    givenUserServiceMapper();
    Consultant patchedConsultant =
        Consultant.builder()
            .matrixUserId(MATRIX_USER_ID)
            .id(CONSULTANT_ID)
            .username("username")
            .firstName("firstname")
            .lastName("lastname")
            .email("email")
            .languageCode(LanguageCode.de)
            .build();
    when(consultantRepository.save(patchedConsultant)).thenReturn(patchedConsultant);

    // when
    patchConsultantSaga.executeTransactional(patchedConsultant, patchMap);

    // then
    verify(consultantRepository).save(patchedConsultant);
    verify(appointmentService).patchConsultant(CONSULTANT_ID, CHANGED_DISPLAY_NAME);
  }

  @Test
  void executeTransactionalOrRollback_ShouldReportAppointmentFailure() {
    // given
    Map<String, Object> patchMap = givenPatchMapWithDisplayName();
    givenUserServiceMapper();
    when(userServiceMapper.displayNameOf(patchMap))
        .thenReturn(java.util.Optional.of(CHANGED_DISPLAY_NAME));
    Consultant patchedConsultant =
        Consultant.builder()
            .matrixUserId(MATRIX_USER_ID)
            .id(CONSULTANT_ID)
            .username("username")
            .firstName("firstname")
            .lastName("lastname")
            .email("email")
            .languageCode(LanguageCode.de)
            .build();
    when(consultantRepository.save(patchedConsultant)).thenReturn(patchedConsultant);
    doThrow(new RuntimeException())
        .when(appointmentService)
        .patchConsultant(Mockito.anyString(), Mockito.anyString());

    try {
      // when
      patchConsultantSaga.executeTransactional(patchedConsultant, patchMap);
      fail("Expected DistributedTransactionException");
    } catch (DistributedTransactionException ex) {
      // then
      verify(consultantRepository).save(patchedConsultant);
      verify(appointmentService).patchConsultant(CONSULTANT_ID, CHANGED_DISPLAY_NAME);
      assertThat(ex.getMessage())
          .contains(TransactionalStep.PATCH_APPOINTMENT_SERVICE_CONSULTANT.name());
    }
  }

  @NotNull
  private Map<String, Object> givenPatchMapWithDisplayName() {
    Map<String, Object> patchMap = Maps.newHashMap();
    patchMap.put("displayName", CHANGED_DISPLAY_NAME);
    return patchMap;
  }

  private void givenUserServiceMapper() {
    when(userServiceMapper.displayNameOf(Mockito.anyMap()))
        .thenReturn(java.util.Optional.of(CHANGED_DISPLAY_NAME));
  }
}
