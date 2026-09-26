/** Persistence model; declares the auto-enabled {@link TenantFilter}. */
@FilterDef(
    name = TenantFilter.NAME,
    autoEnabled = true,
    parameters =
        @ParamDef(
            name = TenantFilter.PARAMETER,
            type = Long.class,
            resolver = TenantFilterParameterResolver.class))
package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.tenant.TenantFilterParameterResolver;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
