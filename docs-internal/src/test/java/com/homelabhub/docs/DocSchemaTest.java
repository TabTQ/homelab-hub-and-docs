package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DocSchemaTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String SHA = "a".repeat(64);
    static final String SHA2 = "b".repeat(64);

    static JsonNode doc(String content) throws Exception {
        return M.readTree("{\"type\":\"doc\",\"content\":[" + content + "]}");
    }

    @Test
    void acceptsEveryBlockTypeAndCollectsReferences() throws Exception {
        JsonNode d = doc("""
                {"type":"heading","attrs":{"level":2,"id":"x"},"content":[{"type":"text","text":"Hi"}]},
                {"type":"paragraph","content":[{"type":"text","text":"bold","marks":[{"type":"bold"},{"type":"link","attrs":{"href":"https://example.org","target":"_blank"}}]},
                  {"type":"hardBreak"},{"type":"pageLink","attrs":{"pageId":7,"label":"Other"}}]},
                {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph"}]}]},
                {"type":"orderedList","attrs":{"start":3},"content":[{"type":"listItem","content":[{"type":"paragraph"}]}]},
                {"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph"}]}]},
                {"type":"blockquote","content":[{"type":"paragraph"}]},{"type":"horizontalRule"},
                {"type":"codeBlock","attrs":{"language":"YAML"},"content":[{"type":"text","text":"a: 1"}]},
                {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":2,"rowspan":1,"colwidth":[100,null]},"content":[{"type":"paragraph"}]}]},
                  {"type":"tableRow","content":[{"type":"tableCell","content":[{"type":"paragraph"}]}]}]},
                {"type":"image","attrs":{"sha":"%s","alt":"net","caption":"Network","width":80}},
                {"type":"callout","attrs":{"type":"warning"},"content":[{"type":"paragraph"}]},
                {"type":"details","attrs":{"open":true},"content":[{"type":"detailsSummary","content":[{"type":"text","text":"More"}]},{"type":"detailsContent","content":[{"type":"paragraph"}]}]},
                {"type":"attachment","attrs":{"sha":"%s","name":"x.pdf","size":10}},
                {"type":"mermaid","attrs":{"source":"flowchart TD\\nA-->B","lightSvg":"%s","darkSvg":"%s"}},
                {"type":"webappEmbed","attrs":{"pageId":9,"height":500}}
                """.formatted(SHA, SHA2, SHA, SHA2));
        DocSchema.Result r = DocSchema.validate(d);
        assertThat(r.refs().assets()).containsExactlyInAnyOrder(SHA, SHA2);
        assertThat(r.refs().pageLinks()).containsExactly(7L);
        assertThat(r.refs().embeds()).containsExactly(9L);
        assertThat(r.doc().at("/content/0/attrs/id").isMissingNode()).as("unknown attributes are dropped").isTrue();
        assertThat(r.doc().at("/content/1/content/0/marks/1/attrs/target").isMissingNode()).isTrue();
        assertThat(r.doc().at("/content/7/attrs/language").asText()).isEqualTo("yaml");
    }

    @Test
    void refusesUnknownNodesAndMarks() throws Exception {
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"html\",\"attrs\":{\"html\":\"<script>\"}}"))).hasMessageContaining("Unsupported block");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"textStyle\"}]}]}")))
                .hasMessageContaining("Unsupported formatting");
        assertThatThrownBy(() -> DocSchema.validate(M.readTree("{\"type\":\"paragraph\"}"))).hasMessageContaining("document");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"doc\"}"))).hasMessageContaining("another document");
    }

    @Test
    void refusesUnsafeLinks() throws Exception {
        for (String href : new String[] {"javascript:alert(1)", " JAVASCRIPT:alert(1)", "data:text/html,x", "vbscript:x", "/relative", "java\tscript:x", ""}) {
            String json = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":" + M.writeValueAsString(href) + "}}]}]}";
            assertThatThrownBy(() -> DocSchema.validate(doc(json))).as(href).hasMessageContaining("Links must start");
        }
        assertThat(DocSchema.safeHref("mailto:me@example.org")).isTrue();
    }

    @Test
    void checksAttributes() throws Exception {
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"heading\",\"attrs\":{\"level\":7}}"))).hasMessageContaining("heading level");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"image\",\"attrs\":{\"sha\":\"../../etc/passwd\"}}"))).hasMessageContaining("image reference");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"callout\",\"attrs\":{\"type\":\"evil\"}}"))).hasMessageContaining("callout");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"pageLink\",\"attrs\":{\"pageId\":-1,\"label\":\"x\"}}"))).hasMessageContaining("page reference");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"codeBlock\",\"attrs\":{\"language\":\"<script>\"}}"))).hasMessageContaining("language");
        assertThatThrownBy(() -> DocSchema.validate(doc("{\"type\":\"image\",\"attrs\":{\"sha\":\"" + SHA + "\"},\"content\":[{\"type\":\"paragraph\"}]}")))
                .hasMessageContaining("cannot contain");
    }

    @Test
    void enforcesSizeAndNodeLimits() throws Exception {
        String many = String.join(",", java.util.Collections.nCopies(DocSchema.MAX_NODES, "{\"type\":\"paragraph\"}"));
        assertThatThrownBy(() -> DocSchema.validate(doc(many))).hasMessageContaining("blocks");
        String big = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(DocSchema.MAX_JSON_BYTES) + "\"}]}";
        assertThatThrownBy(() -> DocSchema.validate(doc(big))).hasMessageContaining("1 MB");
        String deep = "{\"type\":\"blockquote\",\"content\":[".repeat(45) + "{\"type\":\"paragraph\"}" + "]}".repeat(45);
        assertThatThrownBy(() -> DocSchema.validate(doc(deep))).hasMessageContaining("nested");
    }

    @Test
    void emptyDocIsValid() {
        assertThat(DocSchema.validate(DocSchema.empty()).nodes()).isEqualTo(2);
    }
}
