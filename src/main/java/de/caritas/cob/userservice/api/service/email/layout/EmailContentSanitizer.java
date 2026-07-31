package de.caritas.cob.userservice.api.service.email.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Turns the author-written {@code InviteEmailTemplate.body} into the content fragment of the
 * branded layout (ORISO-UserService#914).
 *
 * <p><strong>Template bodies are untrusted input for HTML purposes.</strong> An admin authoring a
 * template writes content, not markup, and must never be able to inject arbitrary HTML into a mail
 * the platform sends in its own name (nor break the surrounding table layout). Everything passes
 * through jsoup with an explicit allow-list:
 *
 * <table>
 *   <caption>What survives sanitisation</caption>
 *   <tr><th>Allowed</th><td>{@code p}, {@code br}, {@code b}, {@code strong}, {@code i}, {@code
 *   em}, {@code u}, {@code ul}, {@code ol}, {@code li}, {@code h1}, {@code h2}, {@code h3},
 *   {@code blockquote}, {@code hr}, {@code a} with {@code href}</td></tr>
 *   <tr><th>Allowed attributes</th><td>only {@code href} on {@code a}, restricted to the {@code
 *   http}, {@code https} and {@code mailto} protocols; {@code target}/{@code rel}/{@code style}
 *   are set by the layout, never by the author</td></tr>
 *   <tr><th>Stripped, text kept</th><td>every other element, e.g. {@code div}, {@code span},
 *   {@code table}, {@code font}</td></tr>
 *   <tr><th>Removed entirely</th><td>{@code script}, {@code style}, {@code iframe}, {@code
 *   object}, comments, and all attributes ({@code style}, {@code class}, {@code id}, {@code
 *   on*}, {@code src}, …)</td></tr>
 * </table>
 *
 * <p>On top of sanitisation two authoring conveniences are applied, in this order:
 *
 * <ol>
 *   <li><em>Plain-text paragraphing</em> — a body without block-level markup (the normal case
 *       today) keeps its line breaks: a blank line starts a new paragraph, a single newline becomes
 *       a {@code <br>}.
 *   <li><em>Linkification</em> — bare {@code http(s)} URLs in text become real anchors, so the
 *       onboarding link is clickable (ORISO-UserService#913). URLs already inside an {@code <a>}
 *       are left alone, so nothing gets double-wrapped.
 * </ol>
 */
@Component
public class EmailContentSanitizer {

  /** Author-facing allow-list — see the class documentation for the rationale. */
  static final Safelist SAFELIST =
      new Safelist()
          .addTags(
              "p",
              "br",
              "b",
              "strong",
              "i",
              "em",
              "u",
              "ul",
              "ol",
              "li",
              "h1",
              "h2",
              "h3",
              "blockquote",
              "hr",
              "a")
          .addAttributes("a", "href")
          .addProtocols("a", "href", "http", "https", "mailto");

  private static final Pattern BARE_URL =
      Pattern.compile("\\bhttps?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

  private static final Pattern BLOCK_LEVEL =
      Pattern.compile("<\\s*(p|br|ul|ol|li|h1|h2|h3|blockquote|hr)\\b", Pattern.CASE_INSENSITIVE);

  private static final String TRAILING_PUNCTUATION = ".,;:!?)]}'\"";

  /**
   * Sanitises, paragraphs and linkifies the author body.
   *
   * @param linkColor colour applied inline to every anchor — mail clients cannot be trusted to
   *     honour a {@code <style>} block, and an unstyled link inherits the surrounding grey
   * @return an HTML fragment safe to place inside the layout's content cell (never {@code null})
   */
  public String toContentHtml(String authorBody, String linkColor) {
    if (authorBody == null || authorBody.isBlank()) {
      return "";
    }
    Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
    String sanitized = Jsoup.clean(authorBody, "", SAFELIST, outputSettings);
    if (sanitized.isBlank()) {
      return "";
    }
    String paragraphed =
        BLOCK_LEVEL.matcher(sanitized).find() ? sanitized : paragraphPlainText(sanitized);

    Document document = Jsoup.parseBodyFragment(paragraphed);
    document.outputSettings(outputSettings);
    linkifyBareUrls(document.body());
    styleAnchors(document.body(), linkColor);
    return document.body().html();
  }

  /**
   * Derives the plain-text alternative from the very same fragment the HTML part is built from —
   * the two parts can therefore never drift apart in content, only in presentation.
   */
  public String toPlainText(String contentHtml) {
    if (contentHtml == null || contentHtml.isBlank()) {
      return "";
    }
    Document document = Jsoup.parseBodyFragment(contentHtml);
    StringBuilder out = new StringBuilder();
    appendPlainText(document.body(), out);
    return collapseBlankLines(out.toString()).trim();
  }

  private static String paragraphPlainText(String sanitized) {
    String normalized = sanitized.replace("\r\n", "\n").replace('\r', '\n');
    String[] paragraphs = normalized.split("\n[ \t]*\n+");
    StringBuilder html = new StringBuilder();
    for (String paragraph : paragraphs) {
      String trimmed = paragraph.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      html.append("<p>").append(trimmed.replace("\n", "<br>")).append("</p>");
    }
    return html.toString();
  }

  private static void linkifyBareUrls(Element root) {
    List<TextNode> candidates = new ArrayList<>();
    collectTextNodes(root, candidates);
    for (TextNode node : candidates) {
      if (node.parent() != null && hasAnchorAncestor(node)) {
        continue;
      }
      String text = node.getWholeText();
      Matcher matcher = BARE_URL.matcher(text);
      if (!matcher.find()) {
        continue;
      }
      matcher.reset();
      StringBuilder replacement = new StringBuilder();
      int cursor = 0;
      while (matcher.find()) {
        String url = stripTrailingPunctuation(matcher.group());
        replacement.append(escapeHtml(text.substring(cursor, matcher.start())));
        replacement
            .append("<a href=\"")
            .append(escapeHtml(url))
            .append("\">")
            .append(escapeHtml(url))
            .append("</a>");
        cursor = matcher.start() + url.length();
      }
      replacement.append(escapeHtml(text.substring(cursor)));
      node.after(replacement.toString());
      node.remove();
    }
  }

  private static void styleAnchors(Element root, String linkColor) {
    String color = EmailColors.normalize(linkColor);
    String effective = color == null ? EmailColors.PLATFORM_ACCENT_DARK : color;
    for (Element anchor : root.select("a[href]")) {
      anchor.attr("target", "_blank");
      anchor.attr("rel", "noopener noreferrer");
      anchor.attr(
          "style", "color:" + effective + ";text-decoration:underline;word-break:break-word;");
    }
  }

  private static void collectTextNodes(Node node, List<TextNode> collected) {
    for (Node child : node.childNodes()) {
      if (child instanceof TextNode textNode) {
        collected.add(textNode);
      } else {
        collectTextNodes(child, collected);
      }
    }
  }

  private static boolean hasAnchorAncestor(Node node) {
    for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
      if (parent instanceof Element element && "a".equalsIgnoreCase(element.tagName())) {
        return true;
      }
    }
    return false;
  }

  private static String stripTrailingPunctuation(String url) {
    String value = url;
    while (!value.isEmpty()
        && TRAILING_PUNCTUATION.indexOf(value.charAt(value.length() - 1)) >= 0) {
      value = value.substring(0, value.length() - 1);
    }
    return value.isEmpty() ? url : value;
  }

  private static void appendPlainText(Node node, StringBuilder out) {
    for (Node child : node.childNodes()) {
      if (child instanceof TextNode textNode) {
        out.append(textNode.getWholeText().replaceAll("[ \t]+", " "));
        continue;
      }
      if (!(child instanceof Element element)) {
        continue;
      }
      String tag = element.tagName().toLowerCase(java.util.Locale.ROOT);
      switch (tag) {
        case "br" -> out.append('\n');
        case "hr" -> out.append("\n----------\n");
        case "li" -> {
          out.append("\n- ");
          appendPlainText(element, out);
        }
        case "a" -> appendAnchorPlainText(element, out);
        case "p", "div", "ul", "ol", "h1", "h2", "h3", "blockquote" -> {
          out.append('\n');
          appendPlainText(element, out);
          out.append('\n');
        }
        default -> appendPlainText(element, out);
      }
    }
  }

  private static void appendAnchorPlainText(Element anchor, StringBuilder out) {
    String href = anchor.attr("href").trim();
    String label = anchor.text().trim();
    if (href.isEmpty() || href.equalsIgnoreCase(label)) {
      out.append(label.isEmpty() ? href : label);
      return;
    }
    out.append(label.isEmpty() ? href : label + " (" + href + ")");
  }

  private static String collapseBlankLines(String value) {
    return value.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").replaceAll("[ \t]+\n", "\n");
  }

  /** Minimal HTML escaper for text we re-insert during linkification. */
  static String escapeHtml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
