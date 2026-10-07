package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink.MethodEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.exception.httpresponses.NoContentException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import org.jeasy.random.FieldPredicates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@TestPropertySource(properties = "spring.profiles.active=testing,verified-request-caller")
@AutoConfigureTestDatabase(replace = Replace.NONE)
public class ConsultantAdminServiceIT {

  private static final String EXISTING_CONSULTANT = "0b3b1cc6-be98-4787-aa56-212259d811b9";
  private static final Boolean FORCE_DELETE_SESSIONS = false;

  @Autowired private ConsultantAdminService consultantAdminService;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @Autowired private de.caritas.cob.userservice.api.port.out.AdminRepository adminRepository;
  @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  @Autowired private org.springframework.core.env.Environment environment;

  @Autowired
  private de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration taskIdentities;

  @MockitoBean(name = "restTemplate")
  private org.springframework.web.client.RestTemplate taskAuthHttp;

  @MockitoBean private org.springframework.security.oauth2.jwt.JwtDecoder taskDecoder;

  @MockitoBean(name = "keycloakRestTemplate")
  private org.springframework.web.client.RestTemplate identityHttp;

  @MockitoBean private CreateConsultantSaga createConsultantSaga;

  @MockitoBean private ConsultantUpdateService consultantUpdateService;

  @MockitoBean private AppointmentService appointmentService;

  @MockitoBean private AccountManager accountManager;

  @Test
  public void findConsultantById_Should_returnExpectedConsultant_When_consultantIdExists() {
    ConsultantAdminResponseDTO consultantById =
        this.consultantAdminService.findConsultantById(EXISTING_CONSULTANT);

    assertThat(consultantById.getEmbedded(), notNullValue());
    assertThat(consultantById.getEmbedded().getEmail(), notNullValue());
    assertThat(consultantById.getEmbedded().getFirstname(), notNullValue());
    assertThat(consultantById.getEmbedded().getLastname(), notNullValue());
    assertThat(consultantById.getEmbedded().getUsername(), notNullValue());
    assertThat(consultantById.getEmbedded().getAbsent(), notNullValue());
    assertThat(consultantById.getEmbedded().getTeamConsultant(), notNullValue());
    assertThat(consultantById.getEmbedded().getFormalLanguage(), notNullValue());
    assertThat(consultantById.getEmbedded().getId(), is(EXISTING_CONSULTANT));
    assertThat(consultantById.getEmbedded().getUpdateDate(), notNullValue());
    assertThat(consultantById.getEmbedded().getCreateDate(), notNullValue());
  }

  @Test
  public void findConsultantById_Should_returnExpectedConsultantLinks_When_consultantIdExists() {
    ConsultantAdminResponseDTO consultantById =
        this.consultantAdminService.findConsultantById(EXISTING_CONSULTANT);

    assertThat(consultantById.getLinks(), notNullValue());
    assertThat(consultantById.getLinks().getSelf(), notNullValue());
    assertThat(
        consultantById.getLinks().getSelf().getHref(),
        endsWith("/useradmin/consultants/" + EXISTING_CONSULTANT));
    assertThat(consultantById.getLinks().getSelf().getMethod(), is(MethodEnum.GET));
    assertThat(consultantById.getLinks().getUpdate(), notNullValue());
    assertThat(
        consultantById.getLinks().getUpdate().getHref(),
        endsWith("/useradmin/consultants/" + EXISTING_CONSULTANT));
    assertThat(consultantById.getLinks().getUpdate().getMethod(), is(MethodEnum.PUT));
    assertThat(consultantById.getLinks().getDelete(), notNullValue());
    assertThat(
        consultantById.getLinks().getDelete().getHref(),
        endsWith("/useradmin/consultants/" + EXISTING_CONSULTANT + "?forceDeleteSessions=false"));
    assertThat(consultantById.getLinks().getDelete().getMethod(), is(MethodEnum.DELETE));
    assertThat(consultantById.getLinks().getAgencies(), notNullValue());
    assertThat(
        consultantById.getLinks().getAgencies().getHref(),
        endsWith("/useradmin/consultants/" + EXISTING_CONSULTANT + "/agencies"));
    assertThat(consultantById.getLinks().getAgencies().getMethod(), is(MethodEnum.GET));
    assertThat(consultantById.getLinks().getAddAgency(), notNullValue());
    assertThat(
        consultantById.getLinks().getAddAgency().getHref(),
        endsWith("/useradmin/consultants/" + EXISTING_CONSULTANT + "/agencies"));
    assertThat(consultantById.getLinks().getAddAgency().getMethod(), is(MethodEnum.POST));
  }

  @Test
  public void findConsultantById_Should_throwNoContentException_When_consultantIdDoesNotExist() {
    assertThrows(
        NoContentException.class,
        () -> {
          this.consultantAdminService.findConsultantById("Invalid");
        });
  }

  @Test
  public void createNewConsultant_Should_useCreatorServiceAndBuildConsultantAdminResponseDTO() {
    CreateConsultantDTO createConsultantDTO =
        new EasyRandom().nextObject(CreateConsultantDTO.class);
    when(this.createConsultantSaga.createNewConsultant(any()))
        .thenReturn(new EasyRandom().nextObject(ConsultantAdminResponseDTO.class));

    ConsultantAdminResponseDTO result =
        this.consultantAdminService.createNewConsultant(createConsultantDTO);

    verify(this.createConsultantSaga, times(1)).createNewConsultant(createConsultantDTO);
    assertThat(result.getLinks(), notNullValue());
    assertThat(result.getEmbedded(), notNullValue());
  }

  @Test
  public void updateConsultant_Should_useUpdateServiceAndBuildConsultantAdminResponseDTO() {
    UpdateAdminConsultantDTO updateConsultantDTO =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    when(this.consultantUpdateService.updateConsultant(any(), any()))
        .thenReturn(new EasyRandom().nextObject(Consultant.class));

    ConsultantAdminResponseDTO result =
        this.consultantAdminService.updateConsultant("id", updateConsultantDTO);

    verify(this.consultantUpdateService, times(1)).updateConsultant(any(), any());
    assertThat(result.getLinks(), notNullValue());
    assertThat(result.getEmbedded(), notNullValue());
  }

  @Test
  @Transactional
  public void markConsultantForDeletion_Should_setDeleteDateForConsultantAndConsultantAgencies() {
    var previousSecurity =
        org.springframework.security.core.context.SecurityContextHolder.getContext();
    var previousRequest =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    var admin =
        de.caritas.cob.userservice.api.tenant.Tenants.in(
            2L,
            () -> adminRepository.findById("382517bc-7b9d-4c44-8d33-6b8638201c98").orElseThrow());
    org.assertj.core.api.Assertions.assertThat(admin.getTenantId()).isEqualTo(2L);
    org.assertj.core.api.Assertions.assertThat(admin.getType())
        .isEqualTo(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    var token =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue(
                "synthetic-human-consultant-deletion")
            .header("alg", "RS256")
            .subject(admin.getId())
            .claim("azp", "app")
            .claim("preferred_username", admin.getUsername())
            .claim("tenantId", admin.getTenantId().toString())
            .claim("realm_access", java.util.Map.of("roles", java.util.List.of("tenant-admin")))
            .issuedAt(java.time.Instant.now())
            .expiresAt(java.time.Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationToken(
            token,
            java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue
                        .TENANT_ADMIN)));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    var requestAttributes =
        new org.springframework.web.context.request.ServletRequestAttributes(request);
    var security =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    security.setAuthentication(authentication);
    org.springframework.security.core.context.SecurityContextHolder.setContext(security);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        requestAttributes);
    try {
      de.caritas.cob.userservice.api.tenant.Tenants.in(
          2L,
          () -> {
            var consultant = givenAPersistedConsultantWithMultipleAgencies();
            de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenTaskGrants(
                taskAuthHttp, taskDecoder, taskIdentities);
            var provider =
                de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenProvider(
                    identityHttp,
                    taskIdentities,
                    environment,
                    objectMapper,
                    body -> java.util.UUID.randomUUID().toString(),
                    id -> {},
                    command -> {});
            provider.seed(
                new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
                    .AccountProjection(
                    consultant.getId(),
                    consultant.getUsername(),
                    consultant.getEmail(),
                    consultant.getFirstName(),
                    consultant.getLastName(),
                    consultant.getTenantId(),
                    "de",
                    true,
                    false,
                    java.util.List.of("consultant"),
                    false));

            this.consultantAdminService.markConsultantForDeletion(consultant.getId(), false);

            var deletedConsultant = consultantRepository.findById(consultant.getId()).orElseThrow();
            assertThat(deletedConsultant.getDeleteDate(), notNullValue());
            assertThat(deletedConsultant.getStatus(), is(ConsultantStatus.IN_DELETION));
            var relations = consultantAgencyRepository.findByConsultantId(consultant.getId());
            org.assertj.core.api.Assertions.assertThat(relations).hasSize(10);
            relations.forEach(ca -> assertThat(ca.getDeleteDate(), notNullValue()));
            org.assertj.core.api.Assertions.assertThat(provider.commands())
                .anySatisfy(
                    command -> {
                      org.assertj.core.api.Assertions.assertThat(command.operation())
                          .isEqualTo("account.deactivate");
                      org.assertj.core.api.Assertions.assertThat(command.target())
                          .isEqualTo(consultant.getId());
                    });
            org.assertj.core.api.Assertions.assertThat(
                    provider.projections().get(consultant.getId()).enabled())
                .isFalse();
            return null;
          });
    } finally {
      requestAttributes.requestCompleted();
      org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
          previousRequest);
      org.springframework.security.core.context.SecurityContextHolder.setContext(previousSecurity);
    }
  }

  private Consultant givenAPersistedConsultantWithMultipleAgencies() {
    var parameters =
        new EasyRandomParameters()
            .stringLengthRange(1, 17)
            .excludeField(FieldPredicates.named("consultantAgencies"))
            .excludeField(FieldPredicates.named("languages"))
            .excludeField(FieldPredicates.named("consultantMobileTokens"))
            .excludeField(FieldPredicates.named("consultantTopics"))
            .excludeField(FieldPredicates.named("deleteDate"))
            .excludeField(FieldPredicates.named("appointments"))
            .excludeField(FieldPredicates.named("sessions"));
    var consultant = new EasyRandom(parameters).nextObject(Consultant.class);
    consultant.setId("c0a1e5e5-1026-4a4a-9d1e-000000000202");
    consultant.setTenantId(2L);
    var persistedConsultant = consultantRepository.save(consultant);
    var consultantAgencies =
        new EasyRandom()
            .objects(ConsultantAgency.class, 10)
            .peek(
                agencyRelation -> {
                  agencyRelation.setId(null);
                  agencyRelation.setAgencyId(1L);
                  agencyRelation.setConsultant(persistedConsultant);
                  agencyRelation.setTenantId(2L);
                  agencyRelation.setDeleteDate(null);
                })
            .collect(Collectors.toList());
    consultantAgencyRepository.saveAll(consultantAgencies);
    persistedConsultant.setConsultantAgencies(new HashSet<>(consultantAgencies));
    return persistedConsultant;
  }
}
