package de.caritas.cob.userservice.api.admin.service.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * page and perPage reach the builder straight from the query string: AdminFilterService clamps them
 * for the PageRequest it issues, but hands the raw values to this builder for the HAL links.
 */
class AdminSearchResultBuilderTest {

  @BeforeEach
  void setUpRequestContext() {
    // WebMvcLinkBuilder resolves links against the current request.
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void buildSearchResult_pageAtIntegerMaxValue_omitsNextLinkRatherThanWrappingNegative() {
    // The next link is built as page + 1. At Integer.MAX_VALUE that wraps to Integer.MIN_VALUE, so
    // the response would advertise a negative page. perPage of 0 zeroes the page window, which is
    // what lets the "is there a next page" check pass this far in the first place.
    var result = buildWith(Integer.MAX_VALUE, 0, 5L);

    assertNull(result.getLinks().getNext());
  }

  @Test
  void buildSearchResult_perPageBelowOne_omitsNextLink() {
    // An empty page window makes page * perPage zero or negative, so any non-empty result set would
    // otherwise look like it has a further page, forever.
    var result = buildWith(1, 0, 5L);

    assertNull(result.getLinks().getNext());
  }

  @Test
  void buildSearchResult_negativePerPage_omitsNextLink() {
    var result = buildWith(1, -10, 5L);

    assertNull(result.getLinks().getNext());
  }

  @Test
  void buildSearchResult_ordinaryPage_stillOffersNextLink() {
    // Guards the bounds above against being over-tightened: normal pagination must keep working.
    var result = buildWith(1, 2, 5L);

    assertNotNull(result.getLinks().getNext());
    assertTrue(result.getLinks().getNext().getHref().contains("page=2"));
  }

  @Test
  void buildSearchResult_lastPage_omitsNextLink() {
    var result = buildWith(3, 2, 5L);

    assertNull(result.getLinks().getNext());
  }

  private de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO buildWith(
      int page, int perPage, long totalCount) {
    return (de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO)
        AdminSearchResultBuilder.getInstance(List.of(), totalCount)
            .withPage(page)
            .withPerPage(perPage)
            .buildSearchResult();
  }
}
