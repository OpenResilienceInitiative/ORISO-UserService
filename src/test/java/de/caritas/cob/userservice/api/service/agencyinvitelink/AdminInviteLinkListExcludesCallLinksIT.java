package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * ORISO-UserService#1487 security review: a call share link and its token must never reach an admin
 * list, whatever the scope (Träger, Beratungsstelle, platform) and whatever the filter.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminInviteLinkListExcludesCallLinksIT {

  private static final long TENANT = 77L;
  private static final long AGENCY = 7701L;
  private static final PageRequest PAGE = PageRequest.of(0, 50);

  @Autowired private AgencyInviteLinkRepository links;

  private String callToken;
  private String tenantToken;
  private String counsellorToken;

  @BeforeEach
  void seed() {
    links.deleteAll();
    tenantToken = save("TENANT", null).getToken();
    counsellorToken = save("COUNSELLOR", "consultant-1").getToken();
    callToken = save("CALL", "consultant-1").getToken();
  }

  @Test
  void tenantAdminList_Should_ShowNoCallLink_ForAnyFilter() {
    for (String kind : new String[] {null, "CALL"}) {
      for (String chatType : new String[] {null, "VIDEO"}) {
        assertNoCallLink(
            links.findAllByTenantIdAndFilters(TENANT, kind, null, chatType, null, PAGE));
      }
    }
    assertThat(tokens(links.findAllByTenantIdAndFilters(TENANT, null, null, null, null, PAGE)))
        .containsExactlyInAnyOrder(tenantToken, counsellorToken);
  }

  @Test
  void agencyAdminList_Should_ShowNoCallLink_ForAnyFilter() {
    for (String kind : new String[] {null, "CALL"}) {
      for (String chatType : new String[] {null, "VIDEO"}) {
        assertNoCallLink(
            links.findAllByTenantIdAndAgencyIdsAndFilters(
                TENANT, List.of(AGENCY), kind, null, chatType, "ACTIVE", PAGE));
      }
    }
  }

  @Test
  void platformAdminList_Should_ShowNoCallLink_AcrossAllTenants() {
    for (Long tenant : new Long[] {null, TENANT}) {
      for (String kind : new String[] {null, "CALL"}) {
        assertNoCallLink(links.findAllByFilters(tenant, kind, null, null, null, PAGE));
      }
    }
    assertThat(tokens(links.findAllByFilters(null, null, null, null, null, PAGE)))
        .contains(tenantToken, counsellorToken);
  }

  private void assertNoCallLink(Page<AgencyInviteLink> page) {
    assertThat(page.getContent()).noneMatch(l -> "CALL".equals(l.getLinkKind()));
    assertThat(tokens(page)).doesNotContain(callToken);
  }

  private static List<String> tokens(Page<AgencyInviteLink> page) {
    return page.getContent().stream().map(AgencyInviteLink::getToken).toList();
  }

  private AgencyInviteLink save(String kind, String consultantId) {
    var link = new AgencyInviteLink();
    link.setToken(UUID.randomUUID().toString().replace("-", ""));
    link.setTenantId(TENANT);
    link.setAgencyId(AGENCY);
    link.setLinkKind(kind);
    link.setChatType("VIDEO");
    link.setAnonymity("ANONYMOUS");
    link.setConsultantId(consultantId);
    link.setCreatedByUserId("creator-1");
    link.setCreateDate(LocalDateTime.now());
    link.setStatus("ACTIVE");
    return links.save(link);
  }
}
