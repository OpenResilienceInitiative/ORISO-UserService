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
 * ORISO-Admin#1321: live-chat links ({@code EXTERNAL_INBOUND}) are created for a whole Träger,
 * without an agency. A Beratungsstellen admin must still see them (read-only in the Admin) — but
 * never another agency's links, and never other Träger-wide link kinds.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgencyAdminSeesTenantChatLinksIT {

  private static final long TENANT = 81L;
  private static final long OTHER_TENANT = 82L;
  private static final long OWN_AGENCY = 8101L;
  private static final long OTHER_AGENCY = 8102L;
  private static final PageRequest PAGE = PageRequest.of(0, 50);

  @Autowired private AgencyInviteLinkRepository links;

  private String tenantChatLink;
  private String ownAgencyLink;
  private String otherAgencyLink;
  private String tenantInvite;
  private String otherTenantChatLink;

  @BeforeEach
  void seed() {
    links.deleteAll();
    tenantChatLink = save(TENANT, null, "EXTERNAL_INBOUND");
    ownAgencyLink = save(TENANT, OWN_AGENCY, "EXTERNAL_INBOUND");
    otherAgencyLink = save(TENANT, OTHER_AGENCY, "EXTERNAL_INBOUND");
    tenantInvite = save(TENANT, null, "TENANT");
    otherTenantChatLink = save(OTHER_TENANT, null, "EXTERNAL_INBOUND");
  }

  @Test
  void agencyAdminList_Should_IncludeTraegerWideChatLinks() {
    assertThat(tokens(agencyAdminList(null))).contains(tenantChatLink, ownAgencyLink);
    assertThat(tokens(agencyAdminList("EXTERNAL_INBOUND"))).contains(tenantChatLink, ownAgencyLink);
  }

  @Test
  void agencyAdminList_Should_NotShowOtherAgenciesOrOtherTenants() {
    assertThat(tokens(agencyAdminList(null))).doesNotContain(otherAgencyLink, otherTenantChatLink);
  }

  @Test
  void agencyAdminList_Should_NotShowOtherTraegerWideLinkKinds() {
    assertThat(tokens(agencyAdminList(null))).doesNotContain(tenantInvite);
  }

  private Page<AgencyInviteLink> agencyAdminList(String kind) {
    return links.findAllByTenantIdAndAgencyIdsAndFilters(
        TENANT, List.of(OWN_AGENCY), kind, null, null, "ACTIVE", PAGE);
  }

  private static List<String> tokens(Page<AgencyInviteLink> page) {
    return page.getContent().stream().map(AgencyInviteLink::getToken).toList();
  }

  private String save(long tenant, Long agency, String kind) {
    var link = new AgencyInviteLink();
    link.setToken(UUID.randomUUID().toString().replace("-", ""));
    link.setTenantId(tenant);
    link.setAgencyId(agency);
    link.setLinkKind(kind);
    link.setChatType("LIVE_CHAT");
    link.setAnonymity("FULL");
    link.setCreatedByUserId("platform-admin");
    link.setCreateDate(LocalDateTime.now());
    link.setStatus("ACTIVE");
    return links.save(link).getToken();
  }
}
