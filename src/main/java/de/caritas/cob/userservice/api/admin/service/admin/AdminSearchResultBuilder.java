package de.caritas.cob.userservice.api.admin.service.admin;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink;
import de.caritas.cob.userservice.api.adapters.web.dto.PaginationLinks;
import de.caritas.cob.userservice.api.admin.service.SearchResultBuilder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import java.util.List;
import java.util.stream.Collectors;

public class AdminSearchResultBuilder
    extends SearchResultBuilder<AdminFilter, AdminSearchResultDTO> {
  private final List<Admin> admins;
  private final long totalCount;

  private AdminSearchResultBuilder(List<Admin> admins, long totalCount) {
    // Admin search paginates through Spring Data, not Hibernate Search, so there is no
    // FullTextQuery to hand to the base class. hasNextPage() is overridden accordingly.
    super();
    this.admins = admins;
    this.totalCount = totalCount;
  }

  public static AdminSearchResultBuilder getInstance(List<Admin> admins, long totalCount) {
    return new AdminSearchResultBuilder(admins, totalCount);
  }

  public AdminSearchResultDTO buildSearchResult() {
    var resultList =
        admins.stream()
            .map(AdminResponseDTOBuilder::getInstance)
            .map(AdminResponseDTOBuilder::buildAgencyAdminResponseDTO)
            .collect(Collectors.toList());

    var paginationLinks =
        new PaginationLinks()
            .self(buildPageLink(page))
            .next(hasNextPage() ? buildPageLink(page + 1) : null)
            .previous(page > 1 ? buildPageLink(page - 1) : null);

    return new AdminSearchResultDTO()
        .embedded(resultList)
        .links(paginationLinks)
        .total(toIntTotal(totalCount));
  }

  /**
   * Narrows the repository's {@code long} count to the {@code int} the generated DTO exposes.
   * Clamping instead of casting avoids the silent wraparound to a negative total that a plain
   * {@code (int)} cast would produce beyond {@link Integer#MAX_VALUE}.
   */
  private static int toIntTotal(long totalCount) {
    return (int) Math.min(totalCount, Integer.MAX_VALUE);
  }

  @Override
  protected boolean hasNextPage() {
    return totalCount > (long) page * perPage;
  }

  private HalLink buildPageLink(int targetPage) {
    return super.buildSelfLink(
        methodOn(UseradminApi.class).getAgencyAdmins(targetPage, perPage, filter, sort));
  }
}
