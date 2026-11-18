package de.caritas.cob.userservice.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Maps;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.consultant.ConsultantMatrixDisplayNamePublisher;
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

  @Mock ConsultantMatrixDisplayNamePublisher consultantMatrixDisplayNamePublisher;

  @Test
  void executeTransactional_Should_SaveConsultant() {
    // given
    Map<String, Object> patchMap = givenPatchMapWithDisplayName();
    givenUserServiceMapper();
    Consultant patchedConsultant = consultant();
    when(consultantRepository.save(patchedConsultant)).thenReturn(patchedConsultant);

    // when
    patchConsultantSaga.executeTransactional(patchedConsultant, patchMap);

    // then
    verify(consultantRepository).save(patchedConsultant);
  }

  // ORISO-UserService#1541: the self-service rename reaches Matrix like the PUT path does.

  @Test
  void executeTransactional_Should_publishTheMatrixDisplayName_When_theDisplayNameIsPatched() {
    Map<String, Object> patchMap = givenPatchMapWithDisplayName();
    givenUserServiceMapper();
    Consultant patchedConsultant = consultant();
    Consultant savedConsultant = consultant();
    when(consultantRepository.save(patchedConsultant)).thenReturn(savedConsultant);

    patchConsultantSaga.executeTransactional(patchedConsultant, patchMap);

    verify(consultantMatrixDisplayNamePublisher).publishAfterCommit(savedConsultant);
  }

  @Test
  void executeTransactional_Should_notPublishToMatrix_When_theDisplayNameIsNotPatched() {
    Map<String, Object> patchMap = Maps.newHashMap();
    patchMap.put("walkThroughEnabled", false);
    when(userServiceMapper.displayNameOf(patchMap)).thenReturn(java.util.Optional.empty());
    Consultant patchedConsultant = consultant();
    when(consultantRepository.save(patchedConsultant)).thenReturn(patchedConsultant);

    patchConsultantSaga.executeTransactional(patchedConsultant, patchMap);

    Mockito.verifyNoInteractions(consultantMatrixDisplayNamePublisher);
  }

  private static Consultant consultant() {
    return Consultant.builder()
        .matrixUserId(MATRIX_USER_ID)
        .id(CONSULTANT_ID)
        .username("username")
        .firstName("firstname")
        .lastName("lastname")
        .email("email")
        .languageCode(LanguageCode.de)
        .build();
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
