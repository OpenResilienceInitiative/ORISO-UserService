package de.caritas.cob.userservice.api.port.out;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} whose offset is a row offset, not a page index.
 *
 * <p>{@code PageRequest} pages in units of the page size, so it can only express offsets that are
 * multiples of it. The candidate-search API takes {@code offset} and {@code count} independently
 * and permits any non-negative offset, so paging it through {@code PageRequest} would silently move
 * the window. Spring Data JPA reads {@link #getOffset()} and {@link #getPageSize()} straight into
 * {@code setFirstResult}/{@code setMaxResults}, so this carries the caller's offset exactly.
 */
public final class OffsetPageable implements Pageable {

  private final long offset;
  private final int size;
  private final Sort sort;

  public OffsetPageable(long offset, int size) {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (size < 1) {
      throw new IllegalArgumentException("size must be at least 1");
    }
    this.offset = offset;
    this.size = size;
    this.sort = Sort.unsorted();
  }

  @Override
  public int getPageNumber() {
    return (int) (offset / size);
  }

  @Override
  public int getPageSize() {
    return size;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return sort;
  }

  @Override
  public Pageable next() {
    return new OffsetPageable(offset + size, size);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetPageable(offset - size, size) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageable(0, size);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    return new OffsetPageable((long) pageNumber * size, size);
  }

  @Override
  public boolean hasPrevious() {
    return offset >= size;
  }
}
