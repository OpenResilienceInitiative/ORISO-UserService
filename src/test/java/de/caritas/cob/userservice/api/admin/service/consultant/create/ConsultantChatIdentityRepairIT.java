package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantResponseDTOBuilder;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The incomplete-then-repaired path of #1194, against the real persistence.
 *
 * <p>Creation without a chat server keeps succeeding — that is the carried operational decision
 * this suite runs on, and {@code CreateConsultantSagaIT} pins it. What must no longer hold is that
 * the resulting record is silent about it: it reports {@code chatIdentityStatus = MISSING}, it is
 * findable, and it can be repaired afterwards into a record indistinguishable from one created
 * while the chat server was up.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantChatIdentityRepairIT {

  private static final String VALID_USERNAME = "chatlessUsername";
  private static final String VALID_EMAILADDRESS = "chatless@emailaddress.de";
  private static final String MATRIX_USER_ID = "@chatlessusername:matrix.local";
  private static final long TENANT_ID = 1L;

  @Autowired private CreateConsultantSaga createConsultantSaga;
  @Autowired private ConsultantChatIdentityService consultantChatIdentityService;
  @Autowired private ConsultantRepository consultantRepository;

  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private MatrixSynapseService matrixSynapseService;
  @MockitoBean private TenantAdminService tenantAdminService;
  @MockitoBean private RollbackFacade rollbackFacade;
  @MockitoBean private AppointmentService appointmentService;

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenantService;

  private final EasyRandom easyRandom = new EasyRandom();

  @BeforeEach
  void setup() {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", false);
    when(tenantService.getRestrictedTenantDataFresh(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
    when(keycloakService.createUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(CreatedIdentity.class));
    when(tenantAdminService.getTenantById(TENANT_ID))
        .thenReturn(new TenantDTO().settings(new Settings().featureGroupChatV2Enabled(false)));
  }

  @Test
  void consultantCreatedWithoutAChatServer_Should_beReportedMissing_findable_andRepairable()
      throws Exception {
    // given: no chat server answers, exactly as in every suite that runs without a Synapse
    when(matrixSynapseService.createUserId(anyString(), anyString(), any())).thenReturn(null);

    // when: an administrator creates a consultant. UserAdminController hands the plain username
    // to the saga through this holder, so setting it is what makes the saga attempt Matrix at all.
    PlainCredentialsHolder.set(VALID_USERNAME, null);
    var created = this.createConsultantSaga.createNewConsultant(newConsultantInput());
    var consultantId = created.getEmbedded().getId();

    // then: creation still succeeds, and the response says the account is not usable for chat
    assertThat(consultantId).isNotNull();
    assertThat(created.getEmbedded().getChatIdentityStatus())
        .isEqualTo(ConsultantDTO.ChatIdentityStatusEnum.MISSING);

    // and: the stored record carries the fact
    var stored = consultantRepository.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();
    assertThat(stored.getMatrixUserId()).isNull();

    // and: the condition is discoverable without a database session
    assertThat(consultantChatIdentityService.findConsultantsWithoutChatIdentity())
        .extracting(c -> c.getId())
        .contains(consultantId);

    // when: the chat server is back and an administrator repairs the account
    when(matrixSynapseService.createUserId(anyString(), anyString(), any()))
        .thenReturn(MATRIX_USER_ID);
    consultantChatIdentityService.provisionMissingChatIdentity(consultantId);

    // then: the persisted record is indistinguishable from one created while Synapse was up
    var repaired = consultantRepository.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();
    assertThat(repaired.getMatrixUserId()).isEqualTo(MATRIX_USER_ID);
    assertThat(
            ConsultantResponseDTOBuilder.getInstance(repaired)
                .buildResponseDTO()
                .getEmbedded()
                .getChatIdentityStatus())
        .isEqualTo(ConsultantDTO.ChatIdentityStatusEnum.PROVISIONED);

    // and: it is gone from the report
    assertThat(consultantChatIdentityService.findConsultantsWithoutChatIdentity())
        .extracting(c -> c.getId())
        .doesNotContain(consultantId);

    // and: repairing twice is safe - no second chat account is provisioned. Two calls in total:
    // the one the saga made while the chat server was down, and the one that repaired it.
    consultantChatIdentityService.provisionMissingChatIdentity(consultantId);
    verify(matrixSynapseService, times(2)).createUserId(anyString(), anyString(), any());
    assertThat(
            consultantRepository
                .findByIdAndDeleteDateIsNull(consultantId)
                .orElseThrow()
                .getMatrixUserId())
        .isEqualTo(MATRIX_USER_ID);
  }

  private CreateConsultantDTO newConsultantInput() {
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);
    return createConsultantDTO;
  }
}
