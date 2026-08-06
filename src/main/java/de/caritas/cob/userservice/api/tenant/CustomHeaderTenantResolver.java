package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class CustomHeaderTenantResolver implements TenantResolver {
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    return tenantHeaderSupplier.getTenantFromHeader();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
