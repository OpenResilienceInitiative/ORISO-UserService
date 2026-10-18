package de.caritas.cob.userservice.api.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Applies {@link WithTenant} around each test and leaves no tenant behind. */
class TenantExtension implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    declaredTenant(context).ifPresent(Tenants::actIn);
    resolveRequestsToTheActingTenant(context);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    Tenants.reset();
  }

  private static Optional<Long> declaredTenant(ExtensionContext context) {
    for (ExtensionContext current = context; current != null; ) {
      Optional<Long> declared = current.getElement().flatMap(TenantExtension::declaredOn);
      if (declared.isPresent()) {
        return declared;
      }
      current = current.getParent().orElse(null);
    }
    return Optional.empty();
  }

  private static Optional<Long> declaredOn(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, WithTenant.class).map(WithTenant::value);
  }

  /**
   * A mocked {@link TenantResolverService} stands in for the access token's tenant claim, or on a
   * public route for the main tenant of a single-domain deployment. Tests without the mock keep the
   * real resolution, e.g. to prove it.
   */
  private static void resolveRequestsToTheActingTenant(ExtensionContext context) {
    if (!context.getTestClass().map(TenantExtension::runsWithSpring).orElse(false)) {
      return;
    }
    var spring = SpringExtension.getApplicationContext(context);
    var resolver = spring.getBeanProvider(TenantResolverService.class).getIfAvailable();
    if (resolver == null || !mockingDetails(resolver).isMock()) {
      return;
    }
    doAnswer(call -> Tenants.acting()).when(resolver).resolve(any());
    // HttpTenantFilter looks the resolved tenant's subdomain up; a test may stub its own answer.
    var tenants = spring.getBeanProvider(TenantService.class).getIfAvailable();
    if (tenants != null && mockingDetails(tenants).isMock()) {
      doAnswer(call -> new RestrictedTenantDTO().id(call.getArgument(0)).subdomain("synthetic"))
          .when(tenants)
          .getRestrictedTenantData(anyLong());
    }
  }

  private static boolean runsWithSpring(Class<?> testClass) {
    return AnnotationSupport.findRepeatableAnnotations(testClass, ExtendWith.class).stream()
        .flatMap(extendWith -> Arrays.stream(extendWith.value()))
        .anyMatch(SpringExtension.class::equals);
  }
}
