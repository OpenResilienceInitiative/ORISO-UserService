package de.caritas.cob.userservice.api.admin.service.search;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.type.StandardBasicTypes;

/** Narrow expression collation: existing storage and unrelated queries remain unchanged. */
public final class SearchTextFunctions implements FunctionContributor {
  @Override
  public void contributeFunctions(FunctionContributions contributions) {
    String expression =
        contributions.getDialect() instanceof MariaDBDialect
            ? "(regexp_replace(convert(?1 using utf8mb4),concat(char(92),'p{M}'),'') collate utf8mb4_uca1400_ai_ci)"
            : contributions.getDialect() instanceof H2Dialect
                ? "admin_search_normalize(?1)"
                : "lower(?1)";
    // H2 uses the same Unicode normalization as query tokens via its testing-profile alias.
    // MariaDB remains the production-engine collation proof.
    contributions
        .getFunctionRegistry()
        .registerPattern(
            "admin_search_text",
            expression,
            contributions
                .getTypeConfiguration()
                .getBasicTypeRegistry()
                .resolve(StandardBasicTypes.STRING));
  }
}
