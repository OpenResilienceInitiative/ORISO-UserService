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
   * Removes an advice seeker's membership in a Beratungsstelle that is being deleted, when they
   * keep their account (ORISO-Admin#1524).
   *
   * @return how many memberships were removed
   */
  @Transactional
  @Modifying(flushAutomatically = true)
  @Query("delete from UserAgency ua where ua.agencyId = :agencyId and ua.user.userId = :userId")
  int deleteMembershipsOfUserInAgency(
      @Param("userId") String userId, @Param("agencyId") Long agencyId);

  /** Whether the advice seeker is a member of another Beratungsstelle. */
  @Query(
      "select count(ua) > 0 from UserAgency ua where ua.user.userId = :userId"
          + " and ua.agencyId <> :agencyId")
  boolean hasMembershipOutsideAgency(
      @Param("userId") String userId, @Param("agencyId") Long agencyId);
}
