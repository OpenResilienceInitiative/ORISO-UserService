-- Only loaded by the H2 testing profile. Keep field and query normalization identical.
CREATE ALIAS IF NOT EXISTS ADMIN_SEARCH_NORMALIZE FOR "de.caritas.cob.userservice.api.admin.service.search.AdminSearchQuery.normalize";
