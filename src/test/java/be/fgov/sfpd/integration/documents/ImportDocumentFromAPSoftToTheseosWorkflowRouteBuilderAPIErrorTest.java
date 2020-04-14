package be.fgov.sfpd.integration.documents;

import be.fgov.sfpd.integration.CamelExtension;
import be.fgov.sfpd.integration.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

public class ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilderAPIErrorTest {

    private static final String[] TEST_FILES = {
            "57030824554D160919T10272372.pdf",
            "57030824554D160919T10272372.xml"
    };

    @RegisterExtension
    public final WireMockExtension wireMock = new WireMockExtension(options()
            .port(8089)
            .usingFilesUnderClasspath("be/fgov/sfpd/integration/documents")
    );

    @RegisterExtension
    public final CamelExtension camel = new CamelExtension(new ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilder());

    @BeforeEach
    public void setProperties(@TempDir Path in) {
        camel.setProperty("theseos.workflow.api.url", "http://localhost:8090/api/workflows");
        camel.setProperty("theseos.workflow.api.authorization", "Bearer {\"user\":\"_SYS_\"}");
        camel.setProperty("camel.documents.input.uri", in.toUri().toASCIIString());
    }

    @Test
    public void shouldMoveFilesToErrorFolderWhenCantConnectToTheseosAPI(@TempDir Path in) throws IOException {

        // copy test files in the directory polled by camel
        for (final String fileName : TEST_FILES) {
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                Files.copy(is, in.resolve(fileName));
            }
        }

        final Path error = in.resolve(".error");

        // wait up to 10 seconds until processed file has been moved to error folder by
        // camel
        await().atMost(10, SECONDS).until(() -> Stream.of(TEST_FILES).anyMatch(f -> Files.exists(error.resolve(f))));
    }

}
