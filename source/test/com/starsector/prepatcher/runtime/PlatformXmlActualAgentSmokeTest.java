package com.starsector.prepatcher.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.helpers.DefaultHandler;

/** Exercises the exact JDK XML/preferences path missed by class-linkage-only tests. */
public final class PlatformXmlActualAgentSmokeTest {
  public static void main(String[] args) throws Exception {
    String status = System.getProperty("starsector.prepatcher.status", "");
    if (status.equals("agent-error") || System.getProperty("starsector.prepatcher.gameNamespace") == null) {
      throw new AssertionError("Agent not installed: " + status);
    }
    byte[] xml = "<root value='test'/>".getBytes(StandardCharsets.UTF_8);
    var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml));
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document),
        new StreamResult(new ByteArrayOutputStream()));
    SAXParserFactory.newInstance().newSAXParser().parse(new ByteArrayInputStream(xml), new DefaultHandler());
    Preferences node = Preferences.userRoot().node("starsector-prepatcher-test-" + ProcessHandle.current().pid());
    try {
      node.put("roundtrip", "linux-java28");
      node.flush();
      node.sync();
      ByteArrayOutputStream exported = new ByteArrayOutputStream();
      node.exportSubtree(exported);
      Preferences.importPreferences(new ByteArrayInputStream(exported.toByteArray()));
      if (!"linux-java28".equals(node.get("roundtrip", null))) throw new AssertionError("Preferences roundtrip");
    } finally {
      node.removeNode();
      Preferences.userRoot().flush();
    }
    for (String key : System.getProperties().stringPropertyNames()) {
      if (key.startsWith("starsector.prepatcher.pipelineFailure.")) {
        throw new AssertionError(key + "=" + System.getProperty(key));
      }
    }
    System.out.println("OK actual-agent DOM/SAX/transformer/preferences import-export-flush: no pipeline failures");
  }
}
