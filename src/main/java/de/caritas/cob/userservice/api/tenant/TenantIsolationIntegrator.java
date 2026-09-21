package de.caritas.cob.userservice.api.tenant;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;

/**
 * Registers {@link TenantScopedLoadByIdListener} with every Hibernate session factory of this
 * service. Discovered by Hibernate through {@code META-INF/services}, so it does not depend on how
 * the session factory is built.
 */
public class TenantIsolationIntegrator implements Integrator {

  @Override
  public void integrate(
      Metadata metadata,
      BootstrapContext bootstrapContext,
      SessionFactoryImplementor sessionFactory) {
    sessionFactory
        .getEventListenerRegistry()
        .appendListeners(EventType.LOAD, new TenantScopedLoadByIdListener());
  }
}
