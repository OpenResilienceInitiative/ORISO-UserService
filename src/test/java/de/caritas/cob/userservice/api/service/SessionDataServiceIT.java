package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.SessionDataInitializingDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration test for {@link SessionDataService} covering the {@code PUT} session-data update
 * path.
 *
 * <p>The test methods are intentionally <b>not</b> annotated with {@code @Transactional}: they must
 * reproduce the production call, where the controller invokes the service without an ambient
 * transaction and {@code spring.jpa.open-in-view=false} leaves no open persistence context. Under
 * those conditions reading the lazily fetched {@code Session.sessionData} collection used to fail
 * with a {@code LazyInitializationException}.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
class SessionDataServiceIT {

  private static final Long SESSION_ID = 1216L;

  @Autowired private SessionDataService sessionDataService;

  @Autowired private SessionDataRepository sessionDataRepository;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @BeforeEach
  void setUp() {
    when(consultingTypeManager.getConsultingTypeSettings(anyInt()))
        .thenReturn(
            new ExtendedConsultingTypeResponseDTO()
                .sessionDataInitializing(new SessionDataInitializingDTO().age(true).state(true)));
  }

  @AfterEach
  void tearDown() {
    sessionDataRepository.deleteAll(sessionDataRepository.findBySessionId(SESSION_ID));
  }

  @Test
  void saveSessionData_Should_PersistSessionData_When_CalledWithoutAmbientTransaction() {
    var sessionData = new SessionDataDTO().age("25").state("8");

    assertThatCode(() -> sessionDataService.saveSessionData(SESSION_ID, sessionData))
        .doesNotThrowAnyException();

    List<SessionData> persisted = sessionDataRepository.findBySessionId(SESSION_ID);
    assertThat(persisted).hasSize(2);
    assertThat(persisted)
        .extracting(SessionData::getKey, SessionData::getValue)
        .containsExactlyInAnyOrder(tuple("age", "25"), tuple("state", "8"));
  }

  @Test
  void saveSessionData_Should_UpdateExistingSessionData_When_CalledTwice() {
    sessionDataService.saveSessionData(SESSION_ID, new SessionDataDTO().age("25").state("8"));

    assertThatCode(
            () ->
                sessionDataService.saveSessionData(
                    SESSION_ID, new SessionDataDTO().age("42").state("9")))
        .doesNotThrowAnyException();

    List<SessionData> persisted = sessionDataRepository.findBySessionId(SESSION_ID);
    assertThat(persisted).hasSize(2);
    assertThat(persisted)
        .extracting(SessionData::getKey, SessionData::getValue)
        .containsExactlyInAnyOrder(tuple("age", "42"), tuple("state", "9"));
  }
}
