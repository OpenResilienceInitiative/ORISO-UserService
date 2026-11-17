package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgency.ConsultantAgencyBase;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Colleague;
import de.caritas.cob.userservice.api.service.calendar.CalendarColleagueScope.Relation;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarColleagueScopeTest {

  private static final long TENANT = 1L;
  private static final long OTHER_TENANT = 2L;
  private static final long AGENCY = 10L;
  private static final long OTHER_AGENCY = 20L;

  @Mock ConsultantRepository consultants;
  @Mock ConsultantAgencyRepository consultantAgencies;
  @Mock CalendarPolicyResolver policies;

  private CalendarColleagueScope scope;
  private final List<Consultant> everyone = new ArrayList<>();
  private Consultant me;
  private Consultant sameAgency;
  private Consultant sameTenant;
  private Consultant otherTenant;

  @BeforeEach
  void setUp() {
    scope = new CalendarColleagueScope(consultants, consultantAgencies, policies);
    me = consultant("me", TENANT, "Me", AGENCY);
    sameAgency = consultant("same-agency", TENANT, "Anna Agency", AGENCY, OTHER_AGENCY);
    sameTenant = consultant("same-tenant", TENANT, "Timo Tenant", OTHER_AGENCY);
    otherTenant = consultant("other-tenant", OTHER_TENANT, "Olga Other", 30L);
    lenient()
        .when(consultantAgencies.findByConsultantIdInAndDeleteDateIsNull(anyList()))
        .thenAnswer(
            call -> {
              List<String> ids = call.getArgument(0);
              var rows = new ArrayList<ConsultantAgencyBase>();
              for (var consultant : everyone) {
                if (ids.contains(consultant.getId())) {
                  for (var relation : consultant.getConsultantAgencies()) {
                    if (relation.getDeleteDate() == null) {
                      rows.add(row(consultant.getId(), relation.getAgencyId()));
                    }
                  }
                }
              }
              return rows;
            });
    lenient()
        .when(consultants.findByConsultantAgenciesAgencyIdInAndDeleteDateIsNull(anyList()))
        .thenReturn(List.of(me, sameAgency));
    lenient()
        .when(consultants.findByTenantIdAndDeleteDateIsNullOrderByFirstNameAscLastNameAsc(TENANT))
        .thenReturn(List.of(me, sameAgency, sameTenant));
    lenient().when(consultants.findByDeleteDateIsNull()).thenReturn(everyone);
  }

  /** scope of my Träger, scope of the other Träger, who must be reachable. */
  @ParameterizedTest
  @CsvSource({
    "AGENCY,   AGENCY,   true,  false, false",
    "AGENCY,   PLATFORM, true,  false, false",
    "TENANT,   PLATFORM, true,  true,  false",
    "PLATFORM, AGENCY,   true,  true,  false",
    "PLATFORM, TENANT,   true,  true,  false",
    "PLATFORM, PLATFORM, true,  true,  true"
  })
  void find_Should_FollowTheScopeMatrix(
      CalendarVisibilityScope mine,
      CalendarVisibilityScope theirs,
      boolean agencyColleague,
      boolean tenantColleague,
      boolean foreignColleague) {
    policies(mine, theirs);

    assertThat(scope.find("me", "same-agency").isPresent()).isEqualTo(agencyColleague);
    assertThat(scope.find("me", "same-tenant").isPresent()).isEqualTo(tenantColleague);
    assertThat(scope.find("me", "other-tenant").isPresent()).isEqualTo(foreignColleague);
  }

  @ParameterizedTest
  @CsvSource({
    "AGENCY,   AGENCY,   same-agency",
    "TENANT,   PLATFORM, same-agency|same-tenant",
    "PLATFORM, AGENCY,   same-agency|same-tenant",
    "PLATFORM, PLATFORM, same-agency|other-tenant|same-tenant"
  })
  void list_Should_NameExactlyThoseTheDirectRequestReaches(
      CalendarVisibilityScope mine, CalendarVisibilityScope theirs, String expected) {
    policies(mine, theirs);

    var listed = scope.list("me", null, 200).colleagues();

    assertThat(listed)
        .extracting(colleague -> colleague.consultant().getId())
        .containsExactly(expected.split("\\|"));
    for (var consultant : everyone) {
      var listedHere =
          listed.stream().anyMatch(found -> found.consultant().getId().equals(consultant.getId()));
      assertThat(scope.find("me", consultant.getId()).isPresent()).isEqualTo(listedHere);
    }
  }

  @Test
  void relationAndAgency_Should_TellHowTheColleagueIsRelated() {
    policies(CalendarVisibilityScope.PLATFORM, CalendarVisibilityScope.PLATFORM);

    var listed = scope.list("me", null, 200).colleagues();

    assertThat(listed)
        .extracting(Colleague::relation)
        .containsExactly(Relation.SAME_AGENCY, Relation.OTHER_TENANT, Relation.SAME_TENANT);
    assertThat(listed).extracting(Colleague::agencyId).containsExactly(AGENCY, 30L, OTHER_AGENCY);
    assertThat(listed)
        .extracting(Colleague::name)
        .containsExactly("Anna Agency", "Olga Other", "Timo Tenant");
  }

  @Test
  void neverTheCallerThemselves_And_NeverSomeoneUnknownOrDeleted() {
    policies(CalendarVisibilityScope.PLATFORM, CalendarVisibilityScope.PLATFORM);

    assertThat(scope.find("me", "me")).isEmpty();
    assertThat(scope.find("me", "nobody")).isEmpty();
    assertThat(scope.find("me", null)).isEmpty();
    assertThat(scope.find("nobody", "same-agency")).isEmpty();
    assertThat(scope.list("me", null, 200).colleagues())
        .noneMatch(colleague -> colleague.consultant().getId().equals("me"));
  }

  @Test
  void agencyScope_Should_IgnoreARemovedCentreRelation() {
    policies(CalendarVisibilityScope.AGENCY, CalendarVisibilityScope.AGENCY);
    sameAgency.getConsultantAgencies().stream()
        .filter(relation -> relation.getAgencyId() == AGENCY)
        .forEach(relation -> relation.setDeleteDate(LocalDateTime.now()));

    assertThat(scope.find("me", "same-agency")).isEmpty();
    assertThat(scope.list("me", null, 200).colleagues()).isEmpty();
  }

  @Test
  void list_Should_SearchTheDisplayNameWithoutCase_And_StopAtTheLimit() {
    policies(CalendarVisibilityScope.TENANT, CalendarVisibilityScope.AGENCY);

    assertThat(scope.list("me", "  TIMO ", 200).colleagues())
        .extracting(Colleague::name)
        .containsExactly("Timo Tenant");
    assertThat(scope.list("me", null, 1).colleagues())
        .extracting(Colleague::name)
        .containsExactly("Anna Agency");
  }

  @Test
  void showAskerUsernames_Should_BeTheSettingOfTheColleaguesOwnTraeger() {
    when(policies.resolve(TENANT))
        .thenReturn(new CalendarPolicy(CalendarVisibilityScope.PLATFORM, true));
    when(policies.resolve(OTHER_TENANT))
        .thenReturn(new CalendarPolicy(CalendarVisibilityScope.PLATFORM, false));

    assertThat(scope.find("me", "same-tenant").orElseThrow().showAskerUsernames()).isTrue();
    assertThat(scope.find("me", "other-tenant").orElseThrow().showAskerUsernames()).isFalse();
  }

  private void policies(CalendarVisibilityScope mine, CalendarVisibilityScope theirs) {
    lenient().when(policies.resolve(TENANT)).thenReturn(new CalendarPolicy(mine, false));
    lenient().when(policies.resolve(OTHER_TENANT)).thenReturn(new CalendarPolicy(theirs, false));
  }

  private Consultant consultant(String id, long tenantId, String name, Long... agencyIds) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenantId);
    consultant.setInternalDisplayName(name);
    consultant.setFirstName("First");
    consultant.setLastName("Last");
    consultant.setConsultantAgencies(new HashSet<>());
    long relationId = everyone.size() * 10L;
    for (Long agencyId : agencyIds) {
      var relation = ConsultantAgency.builder().consultant(consultant).agencyId(agencyId).build();
      relation.setId(++relationId);
      consultant.getConsultantAgencies().add(relation);
    }
    everyone.add(consultant);
    lenient().when(consultants.findByIdAndDeleteDateIsNull(id)).thenReturn(Optional.of(consultant));
    return consultant;
  }

  private static ConsultantAgencyBase row(String consultantId, Long agencyId) {
    return new ConsultantAgencyBase() {
      @Override
      public Long getId() {
        return agencyId;
      }

      @Override
      public Long getAgencyId() {
        return agencyId;
      }

      @Override
      public String getConsultantId() {
        return consultantId;
      }

      @Override
      public LocalDateTime getDeleteDate() {
        return null;
      }
    };
  }
}
