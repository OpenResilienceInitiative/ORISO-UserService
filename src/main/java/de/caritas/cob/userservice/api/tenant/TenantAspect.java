package de.caritas.cob.userservice.api.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${multitenancy.enabled:true}")
public class TenantAspect {

  @PersistenceContext private final @NonNull EntityManager entityManager;

  @Before("execution(* de.caritas.cob.userservice.api.port..*(..)))")
  public void beforeQueryAspect() {
    var session = entityManager.unwrap(Session.class);

    if (TenantContext.isTechnicalOrSuperAdminContext()) {
      session.disableFilter("tenantFilter");
      return;
    }

    var filter = session.enableFilter("tenantFilter");
    filter.setParameter("tenantId", TenantContext.getCurrentTenant());
    filter.validate();
  }
}
