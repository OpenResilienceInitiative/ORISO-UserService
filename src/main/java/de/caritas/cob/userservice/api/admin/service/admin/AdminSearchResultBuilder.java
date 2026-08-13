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

  /**
   * Whether a further page exists <em>and</em> can actually be named.
   *
   * <p>page and perPage arrive straight from the query string. AdminFilterService clamps them for
   * the PageRequest it issues, but passes the raw values here, and the API contract sets no minimum
   * or maximum. Two values break the "next" link that {@link #buildSearchResult()} derives as
   * {@code page + 1}:
   *
   * <ul>
   *   <li>perPage below 1 empties the page window, so {@code page * perPage} is zero or negative
   *       and every non-empty result set claims a further page.
   *   <li>page at {@link Integer#MAX_VALUE} cannot be incremented: {@code page + 1} wraps to {@link
   *       Integer#MIN_VALUE} and the response advertises a negative page. The wrap is invisible to
   *       the count comparison below, which widens to {@code long} before multiplying.
   * </ul>
   */
  @Override
  protected boolean hasNextPage() {
    if (page == null || perPage == null || page < 1 || perPage < 1 || page == Integer.MAX_VALUE) {
      return false;
    }
    return totalCount > (long) page * perPage;
  }

  private HalLink buildPageLink(int targetPage) {
    return super.buildSelfLink(
        methodOn(UseradminApi.class).getAgencyAdmins(targetPage, perPage, filter, sort));
  }
}
