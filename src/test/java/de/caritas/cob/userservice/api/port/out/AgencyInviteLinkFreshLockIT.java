package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkKind;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * ORISO-Frontend#2252 with FE#2209's fresh row lock: a link this transaction created or read before
 * keeps its changes and its replaced tokens through the lock, and saves like any other.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AgencyInviteLinkFreshLockIT {

  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private EntityManager entityManager;

  private AgencyInviteLink savedLink(boolean flushed) {
    var link =
        links.save(
            AgencyInviteLink.builder()
                .token("fresh-lock-token")
                .tenantId(1L)
                .linkKind(InviteLinkKind.COUNSELLOR.name())
                .chatType(InviteLinkChatType.VIDEO.name())
                .anonymity("NONE")
                .consultantId("fresh-lock-owner")
                .createdByUserId("fresh-lock-owner")
                .createDate(LocalDateTime.now())
                .status(InviteLinkStatus.ACTIVE.name())
                .build());
    if (flushed) {
      entityManager.flush();
    }
    return link;
  }

  @Test
  void linkCreatedEarlierInTheTransaction_Should_BeSavableAfterItsRowLock() {
    var id = savedLink(false).getId();

    var locked = links.findByIdForUpdate(id).orElseThrow();
    locked.setStatus(InviteLinkStatus.DISABLED.name());
    links.save(locked);
    entityManager.flush();
    entityManager.clear();

    assertThat(links.findById(id).orElseThrow().getStatus())
        .isEqualTo(InviteLinkStatus.DISABLED.name());
  }

  @Test
  void renewedLinkReadEarlierInTheTransaction_Should_KeepItsReplacedTokensAfterItsRowLock() {
    var link = savedLink(true);
    link.getReplacedTokens().add(link.getToken());
    link.setToken("a-new-token");
    links.save(link);
    entityManager.flush();

    var locked = links.findByIdForUpdate(link.getId()).orElseThrow();
    locked.setName("renamed");
    links.save(locked);
    entityManager.flush();
    entityManager.clear();

    assertThat(links.findById(link.getId()).orElseThrow().getReplacedTokens())
        .containsExactly("fresh-lock-token");
  }
}
