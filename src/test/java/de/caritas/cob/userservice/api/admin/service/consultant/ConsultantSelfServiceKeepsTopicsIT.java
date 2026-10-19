package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** A counsellor saving their own profile (PUT /users/data) must keep the admin-set topics. */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ConsultantSelfServiceKeepsTopicsIT {

  // Seeded with agencies 0, 1, 257, ... (UserServiceDatabase.sql).
  private static final String CONSULTANT_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";
  private static final long TOPIC = 9102L;

  @Autowired private ConsultantUpdateService consultantUpdateService;
  @Autowired private ConsultantDtoMapper consultantDtoMapper;
  @Autowired private ConsultantTopicRepository consultantTopicRepository;
  @Autowired private ConsultantRepository consultantRepository;

  @MockitoBean private AgencyService agencyService;
  @MockitoBean private AppointmentService appointmentService;

  @BeforeEach
  void everyAgencyOffersTheTopic() {
    var tenantId = consultantRepository.findById(CONSULTANT_ID).orElseThrow().getTenantId();
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            invocation ->
                invocation.<List<Long>>getArgument(0).stream()
                    .map(id -> new AgencyDTO().id(id).tenantId(tenantId).topicIds(List.of(TOPIC)))
                    .toList());
  }

  @Test
  void selfServiceProfileEdit_keepsTheTopicsTheAdminSet() {
    consultantUpdateService.updateConsultant(
        CONSULTANT_ID,
        new UpdateAdminConsultantDTO()
            .firstname("Multiple")
            .lastname("BS")
            .email("multiple@consultant.de")
            .formalLanguage(true)
            .absent(false)
            .topicIds(List.of(TOPIC)));
    assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
        .as("precondition: the admin assigned the topic")
        .containsExactly(TOPIC);
    var consultant = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    var selfService =
        consultantDtoMapper.updateAdminConsultantOf(
            new UpdateConsultantDTO()
                .firstname("Multiple")
                .lastname("BS")
                .email("multiple@consultant.de"),
            consultant);

    consultantUpdateService.updateConsultant(CONSULTANT_ID, selfService, false);

    assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
        .containsExactly(TOPIC);
  }
}
