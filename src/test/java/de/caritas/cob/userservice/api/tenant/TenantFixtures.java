package de.caritas.cob.userservice.api.tenant;

import com.google.common.collect.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.UUID;

/**
 * Synthetic accounts placed in a Träger: every tenant-filtered row carries the tenant it is seeded
 * in, so the filter shows it to exactly that Träger. Add with
 * {@code @Import(TenantFixtures.class)}; a test without a rolled-back transaction calls {@link
 * #removeAll()} afterwards.
 */
public class TenantFixtures {

  private final ConsultantRepository consultantRepository;
  private final ConsultantAgencyRepository consultantAgencyRepository;
  private final UserRepository userRepository;
  private final SessionRepository sessionRepository;
  private final AdminRepository adminRepository;
  private final AdminAgencyRepository adminAgencyRepository;

  private final Deque<Runnable> removals = new ArrayDeque<>();

  public TenantFixtures(
      ConsultantRepository consultantRepository,
      ConsultantAgencyRepository consultantAgencyRepository,
      UserRepository userRepository,
      SessionRepository sessionRepository,
      AdminRepository adminRepository,
      AdminAgencyRepository adminAgencyRepository) {
    this.consultantRepository = consultantRepository;
    this.consultantAgencyRepository = consultantAgencyRepository;
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
    this.adminRepository = adminRepository;
    this.adminAgencyRepository = adminAgencyRepository;
  }

  /** A counsellor of this Träger, member of the given agencies. */
  public Consultant consultant(long tenantId, Long... agencyIds) {
    return Tenants.in(
        tenantId,
        () -> {
          var id = UUID.randomUUID().toString();
          var consultant = new Consultant();
          consultant.setId(id);
          consultant.setTenantId(tenantId);
          consultant.setUsername("synthetic-" + id.substring(0, 8));
          consultant.setFirstName("Synthetic");
          consultant.setLastName("C" + id.substring(0, 8));
          consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
          consultant.setAppointments(null);
          consultant.setConsultantAgencies(new HashSet<>());
          consultant.setConsultantMobileTokens(new HashSet<>());
          consultant.setEncourage2fa(true);
          consultant.setNotifyEnquiriesRepeating(true);
          consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
          consultant.setWalkThroughEnabled(true);
          consultant.setTeamConsultant(false);
          consultant.setMagicLinkLoginEnabled(false);
          consultant.setLanguageCode(LanguageCode.de);
          consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
          var saved = consultantRepository.save(consultant);
          // Also the relations the code under test added, else the counsellor cannot go.
          removeLater(
              () -> {
                consultantAgencyRepository.deleteAll(
                    consultantAgencyRepository.findByConsultantId(id));
                consultantRepository.deleteById(id);
              });
          for (Long agencyId : agencyIds) {
            consultantAgencyRepository.save(
                ConsultantAgency.builder()
                    .consultant(saved)
                    .agencyId(agencyId)
                    .tenantId(tenantId)
                    .createDate(LocalDateTime.now())
                    .updateDate(LocalDateTime.now())
                    .build());
          }
          return saved;
        });
  }

  /** An admin of this Träger; an agency admin administers the given agencies. */
  public Admin admin(long tenantId, AdminType type, Long... agencyIds) {
    return Tenants.in(
        tenantId,
        () -> {
          var id = UUID.randomUUID().toString();
          var admin =
              adminRepository.save(
                  Admin.builder()
                      .id(id)
                      .tenantId(tenantId)
                      .username("synthetic-admin-" + id.substring(0, 8))
                      .firstName("Synthetic")
                      .lastName("A" + id.substring(0, 8))
                      .email(id.substring(0, 8) + "@synthetic.oriso.test")
                      .type(type)
                      .build());
          removeLater(
              () -> {
                adminAgencyRepository.deleteAll(adminAgencyRepository.findByAdminId(id));
                adminRepository.deleteById(id);
              });
          for (Long agencyId : agencyIds) {
            adminAgencyRepository.save(
                AdminAgency.builder().admin(admin).agencyId(agencyId).build());
          }
          return admin;
        });
  }

  /** A registered advice seeker of this Träger, without a session yet. */
  public User adviceSeeker(long tenantId) {
    return Tenants.in(
        tenantId,
        () -> {
          var id = UUID.randomUUID().toString();
          var user =
              new User(
                  id,
                  null,
                  "synthetic-asker-" + id.substring(0, 8),
                  id.substring(0, 8) + "@synthetic.oriso.test",
                  false);
          user.setTenantId(tenantId);
          user.setLanguageCode(LanguageCode.de);
          user.setEncourage2fa(true);
          var saved = userRepository.save(user);
          removeLater(() -> userRepository.deleteById(id));
          return saved;
        });
  }

  /**
   * A running counselling session of the advice seeker in this agency, in the advice seeker's
   * Träger; {@code advisor} may be null for an open enquiry.
   */
  public Session session(User adviceSeeker, long agencyId, Consultant advisor) {
    long tenantId = adviceSeeker.getTenantId();
    return Tenants.in(
        tenantId,
        () -> {
          var session = new Session();
          session.setUser(adviceSeeker);
          session.setConsultant(advisor);
          session.setTenantId(tenantId);
          session.setAgencyId(agencyId);
          session.setConsultingTypeId(1);
          session.setStatus(SessionStatus.IN_PROGRESS);
          session.setRegistrationType(RegistrationType.REGISTERED);
          session.setPostcode("12345");
          session.setLanguageCode(LanguageCode.de);
          session.setTeamSession(false);
          session.setSessionTopics(Lists.newArrayList());
          session.setIsConsultantDirectlySet(false);
          var saved = sessionRepository.save(session);
          removeLater(() -> sessionRepository.deleteById(saved.getId()));
          return saved;
        });
  }

  /** Deletes what this instance seeded, newest first, in every Träger. */
  public void removeAll() {
    Tenants.acrossAll(
        () -> {
          while (!removals.isEmpty()) {
            removals.pop().run();
          }
        });
  }

  private void removeLater(Runnable removal) {
    removals.push(removal);
  }
}
