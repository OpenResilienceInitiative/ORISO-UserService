package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserAgencyRepository extends CrudRepository<UserAgency, Long> {

  List<UserAgency> findByUser(User user);

  List<UserAgency> findByUserUserIdIn(Collection<String> userIds);

  /**
   * Removes the memberships a deleted Beratungsstelle leaves with advice seekers who keep their
   * account (ORISO-Admin#1524). Those of advice seekers marked for deletion go with them.
   *
   * @return how many memberships were removed
   */
  @Transactional
  @Modifying(flushAutomatically = true)
  @Query(
      "delete from UserAgency ua where ua.agencyId = :agencyId"
          + " and ua.user.userId in (select u.userId from User u where u.deleteDate is null"
          + " and u.userId not like 'group-chat-system%')")
  int deleteMembershipsOfKeptAdviceSeekers(@Param("agencyId") Long agencyId);

  /** Advice seekers who keep their account but lose this membership (ORISO-Admin#1524). */
  @Query(
      "select count(ua) from UserAgency ua where ua.agencyId = :agencyId"
          + " and ua.user.userId in (select u.userId from User u where u.deleteDate is null"
          + " and u.userId not like 'group-chat-system%')")
  long countMembershipsOfKeptAdviceSeekers(@Param("agencyId") Long agencyId);
}
