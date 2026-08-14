package de.caritas.cob.userservice.api.service.draft;

import java.util.regex.Pattern;

/**
 * Decides whether a draft carries content worth persisting.
 *
 * <p>A plain {@code isBlank()} check treats TipTap's empty document ({@code <p></p>}) as content,
 * so merely opening a conversation and leaving it persisted a zero-content draft row: the drafts
 * badge counted the row, the drafts centre could not resolve it to a session, and nothing the user
 * did could clear it (#983, ORISO-Frontend#976).
 *
 * <p>This mirrors the frontend's {@code hasDraftContent} (ORISO-Frontend {@code
 * src/services/draftStore.ts}), which is the reference for what counts as content: markup, HTML
 * whitespace entities and invisible whitespace do not count. An end-to-end encrypted draft is
 * opaque ciphertext without markup and therefore always counts — it must never be mistaken for an
 * empty draft.
 */
public final class DraftContent {

  private static final Pattern MARKUP_TAG = Pattern.compile("<[^>]*>");

  private static final Pattern WHITESPACE_ENTITY =
      Pattern.compile("&nbsp;|&#160;", Pattern.CASE_INSENSITIVE);

  /** Non-breaking space and zero-width space — invisible, and therefore not content. */
  private static final Pattern INVISIBLE_WHITESPACE = Pattern.compile("[ ​]");

  private DraftContent() {}

  /**
   * @param text the raw draft text as stored or submitted
   * @return {@code true} if the draft carries content a user would recognise as a draft
   */
  public static boolean hasContent(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    var stripped = MARKUP_TAG.matcher(text).replaceAll(" ");
    stripped = WHITESPACE_ENTITY.matcher(stripped).replaceAll(" ");
    stripped = INVISIBLE_WHITESPACE.matcher(stripped).replaceAll(" ");
    return !stripped.trim().isEmpty();
  }
}
