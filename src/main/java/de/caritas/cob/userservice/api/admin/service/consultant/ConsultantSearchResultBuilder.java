package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink;
import de.caritas.cob.userservice.api.adapters.web.dto.PaginationLinks;
import de.caritas.cob.userservice.api.admin.service.SearchResultBuilder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builder class to generate a {@link ConsultantSearchResultDTO} containing available hal links and
 * the consultant result elements.
 */
public class ConsultantSearchResultBuilder
    extends SearchResultBuilder<ConsultantFilter, ConsultantSearchResultDTO> {

  private final List<Consultant> consultants;
  private final long totalCount;

  private ConsultantSearchResultBuilder(List<Consultant> consultants, long totalCount) {
    super(null);
    this.consultants = consultants;
    this.totalCount = totalCount;
  }

  /**
   * Creates the {@link ConsultantSearchResultBuilder} instance.
   *
   * @param consultants the consultants of the current page
   * @param totalCount the total amount of matching consultants
   * @return a instance of {@link ConsultantSearchResultBuilder}
   */
  public static ConsultantSearchResultBuilder getInstance(
      List<Consultant> consultants, long totalCount) {
    return new ConsultantSearchResultBuilder(consultants, totalCount);
  }

  /**
   * Generates the {@link ConsultantSearchResultDTO} containing all results and navigation hal
   * links.
   *
   * @return the generated {@link ConsultantSearchResultDTO}
   */
  @Override
  public ConsultantSearchResultDTO buildSearchResult() {
    var resultList =
        consultants.stream()
            .map(ConsultantResponseDTOBuilder::getInstance)
            .map(ConsultantResponseDTOBuilder::buildResponseDTO)
            .collect(Collectors.toList());

    var paginationLinks =
        new PaginationLinks()
            .self(buildPageLink(page))
            .next(hasNext() ? buildPageLink(page + 1) : null)
            .previous(page > 1 ? buildPageLink(page - 1) : null);

    return new ConsultantSearchResultDTO()
        .embedded(resultList)
        .links(paginationLinks)
        .total((int) totalCount);
  }

  private boolean hasNext() {
    return totalCount > (long) page * perPage;
  }

  private HalLink buildPageLink(int targetPage) {
    return super.buildSelfLink(
        methodOn(UseradminApi.class).getConsultants(targetPage, perPage, filter, sort));
  }
}
