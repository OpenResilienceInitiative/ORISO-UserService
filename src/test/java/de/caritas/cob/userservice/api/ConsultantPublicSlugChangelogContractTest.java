package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;

class ConsultantPublicSlugChangelogContractTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/0067_consultant_public_slug/0067_changeSet.xml";

  @Test
  void manuallyAppliedPublicSlugSchemaShouldMarkChangesetAsRan() throws Exception {
    Document changelog = readChangelog();
    var xpath = XPathFactory.newInstance().newXPath();
    String changeSet =
        "/*[local-name()='databaseChangeLog']"
            + "/*[local-name()='changeSet' and @id='consultantPublicSlug']";

    String onFail =
        xpath.evaluate(changeSet + "/*[local-name()='preConditions']/@onFail", changelog);
    Double matchingColumnGuard =
        (Double)
            xpath.evaluate(
                "count("
                    + changeSet
                    + "/*[local-name()='preConditions']"
                    + "/*[local-name()='not']"
                    + "/*[local-name()='columnExists'"
                    + " and @tableName='consultant'"
                    + " and @columnName='public_slug'])",
                changelog,
                XPathConstants.NUMBER);

    assertThat(onFail).isEqualTo("MARK_RAN");
    assertThat(matchingColumnGuard).isEqualTo(1.0);
  }

  private Document readChangelog() throws Exception {
    try (InputStream input = new ClassPathResource(CHANGELOG).getInputStream()) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(input);
    }
  }
}
