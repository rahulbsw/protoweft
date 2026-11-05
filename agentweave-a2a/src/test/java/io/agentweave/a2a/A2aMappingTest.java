package io.agentweave.a2a;

import io.agentweave.core.tool.Content;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class A2aMappingTest {

    @Test
    void inlineFilesKeepTheirNameBothWays() {
        Content.Data data = new Content.Data("text/plain", "aGk=", "output.txt");

        FilePart part = (FilePart) A2aMapping.toPart(data);

        assertThat(part.file()).isInstanceOfSatisfying(FileWithBytes.class,
                f -> assertThat(f.name()).isEqualTo("output.txt"));
        assertThat(A2aMapping.toContent(part)).isEqualTo(data);
    }

    @Test
    void unnamedInlineFilesStayUnnamed() {
        Content.Data data = new Content.Data("image/png", "AAAA");

        assertThat(A2aMapping.toContent(A2aMapping.toPart(data))).isEqualTo(data);
    }

    @Test
    void resourcesTravelAsNamedFiles() {
        FilePart part = (FilePart) A2aMapping.toPart(
                Content.Resource.text("https://example.com/docs/notes.md", "text/markdown", "# hi"));

        assertThat(part.file()).isInstanceOfSatisfying(FileWithBytes.class, f -> {
            assertThat(f.name()).isEqualTo("notes.md");
            assertThat(f.mimeType()).isEqualTo("text/markdown");
            assertThat(new String(Base64.getDecoder().decode(f.bytes()))).isEqualTo("# hi");
        });
    }
}
