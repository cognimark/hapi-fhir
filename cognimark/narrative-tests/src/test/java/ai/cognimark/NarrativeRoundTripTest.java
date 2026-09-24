package ai.cognimark;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Created by Codex
class NarrativeRoundTripTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><pre>First&#13;&#10;  Second&#13;&#10;</pre></div>",
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><pre>First\r\n  Second\r\n</pre></div>",
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>&#65; &amp; &#x42;</p></div>",
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><p title='Example'>Text<br /></p></div>",
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>&#x1F600; &#160; &quot;</p></div>",
        "<div xmlns=\"http://www.w3.org/1999/xhtml\"><!-- comment --><p>A\t  B</p></div>"
    })
    void jsonRoundTripsPreserveTheCompleteNarrativeString(String theNarrative) throws Exception {
        IParser parser = context().newJsonParser();
        String body = body(theNarrative);
        for (int round = 0; round < 3; round++) {
            Patient patient = parser.parseResource(Patient.class, body);
            body = parser.encodeResourceToString(patient);
            assertThat(JSON.readTree(body).path("text").path("div").asText()).isEqualTo(theNarrative);
        }
    }

    @Test
    void defaultParserBehaviorIsUnchanged() throws Exception {
        FhirContext context = FhirContext.forR4();
        assertThat(context.getParserOptions().isPreserveJsonXhtmlSource()).isFalse();
        String source = "<div xmlns=\"http://www.w3.org/1999/xhtml\">&#65;</div>";
        IParser parser = context.newJsonParser();
        String encoded = parser.encodeResourceToString(parser.parseResource(body(source)));
        assertThat(JSON.readTree(encoded).path("text").path("div").asText())
            .isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">A</div>");
    }

    @Test
    void modelEditsAreNotReplacedByTheOldSource() throws Exception {
        IParser parser = context().newJsonParser();
        String original = "<div xmlns=\"http://www.w3.org/1999/xhtml\">&#65;</div>";
        Patient patient = parser.parseResource(Patient.class, body(original));
        patient.getText().getDiv().getChildNodes().get(0).setContent("Updated");
        String encoded = parser.encodeResourceToString(patient);
        assertThat(JSON.readTree(encoded).path("text").path("div").asText())
            .isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">Updated</div>");
        patient.getText().getDiv().getChildNodes().get(0).setContent("A");
        encoded = parser.encodeResourceToString(patient);
        assertThat(JSON.readTree(encoded).path("text").path("div").asText())
            .isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">A</div>");
    }

    @Test
    void malformedNarrativesStillFailDuringParsing() throws Exception {
        String malformed = body("<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>Unclosed</div>");
        assertThatThrownBy(() -> context().newJsonParser().parseResource(malformed))
            .isInstanceOf(DataFormatException.class);
    }

    @Test
    void sourceIsAttachedToEachNarrativeNotToAResourceTypeOrGlobalCache() throws Exception {
        String one = "<div xmlns=\"http://www.w3.org/1999/xhtml\">&#65;</div>";
        String two = "<div xmlns=\"http://www.w3.org/1999/xhtml\">&#x41;</div>";
        String input = JSON.writeValueAsString(Map.of("resourceType", "Bundle", "type", "collection",
            "entry", List.of(Map.of("resource", JSON.readTree(body(one))),
                Map.of("resource", JSON.readTree(body(two))))));
        IParser parser = context().newJsonParser();
        Bundle bundle = parser.parseResource(Bundle.class, input);
        String encoded = parser.encodeResourceToString(bundle);
        assertThat(JSON.readTree(encoded).at("/entry/0/resource/text/div").asText()).isEqualTo(one);
        assertThat(JSON.readTree(encoded).at("/entry/1/resource/text/div").asText()).isEqualTo(two);
    }

    @Test
    void disablingPreservationAndSuppressingNarrativesRemainEffective() throws Exception {
        FhirContext context = context();
        IParser parser = context.newJsonParser();
        Patient patient = parser.parseResource(Patient.class,
            body("<div xmlns=\"http://www.w3.org/1999/xhtml\">&#65;</div>"));
        context.getParserOptions().setPreserveJsonXhtmlSource(false);
        assertThat(JSON.readTree(parser.encodeResourceToString(patient)).path("text").path("div").asText())
            .isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">A</div>");
        context.getParserOptions().setPreserveJsonXhtmlSource(true);
        parser.setSuppressNarratives(true);
        assertThat(JSON.readTree(parser.encodeResourceToString(patient)).path("text").has("div")).isFalse();
    }

    @Test
    void nonXhtmlPlainTextKeepsTheNormalWrappingBehavior() throws Exception {
        IParser parser = context().newJsonParser();
        String encoded = parser.encodeResourceToString(parser.parseResource(body("Example")));
        assertThat(JSON.readTree(encoded).path("text").path("div").asText())
            .isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">Example</div>");
    }

    private static FhirContext context() {
        FhirContext context = FhirContext.forR4();
        context.getParserOptions().setPreserveJsonXhtmlSource(true);
        return context;
    }

    private static String body(String theNarrative) throws Exception {
        return JSON.writeValueAsString(Map.of("resourceType", "Patient", "id", "synthetic",
            "text", Map.of("status", "additional", "div", theNarrative)));
    }
}
