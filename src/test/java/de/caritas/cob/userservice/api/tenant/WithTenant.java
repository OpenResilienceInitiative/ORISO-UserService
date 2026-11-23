package de.caritas.cob.userservice.api.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The test acts as a caller of this Träger, as a request would: its thread runs in the tenant, and
 * every request it sends resolves to it. {@code 0} is the technical tenant that sees every Träger.
 * On a method it overrides the class. See {@link Tenants} for switching inside a test.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@ExtendWith(TenantExtension.class)
public @interface WithTenant {

  long value();
}
