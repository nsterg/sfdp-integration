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

public class ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilderTest {

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
        camel.setProperty("theseos.workflow.api", "http://localhost:8089/api/workflows");
        camel.setProperty("camel.documents.input.uri", in.toUri().toASCIIString());
    }

    @Test
    public void test(@TempDir Path in) throws IOException {

        // copy test files in the directory polled by camel
        for (String fileName : TEST_FILES) {
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                Files.copy(is, in.resolve(fileName));
            }
        }

        Path camel = in.resolve(".camel");

        // wait up to 3 seconds until all files have been processed (moved by camel)
        await()
                .atMost(3, SECONDS)
                .until(() -> Stream.of(TEST_FILES).allMatch(f -> Files.exists(camel.resolve(f)) && !Files.exists(in.resolve(f))));
    }
}
