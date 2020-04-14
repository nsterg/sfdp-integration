package be.fgov.sfpd.integration.documents;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import be.fgov.sfpd.integration.CamelExtension;
import be.fgov.sfpd.integration.WireMockExtension;

public class ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilderTest {

    private static final String[] TEST_FILES = {
            "57030824554D160919T10272372.pdf",
            "57030824554D160919T10272372.xml"
    };

    private static final String[] INVALID_XML_TEST_FILES = {
            "57030824554D160919T10272373.pdf",
            "57030824554D160919T10272373.xml"
    };

    private static final String[] INVALID_FILENAME_TEST_FILES = {
            "57030824554D160919T10272373.pdf",
            "INVALID_FILENAME.xml"
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
    public void shouldProcessAndMoveFilesToSuccessFolder(@TempDir Path in) throws IOException {

        for (final String fileName : TEST_FILES) {
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                Files.copy(is, in.resolve(fileName));
            }
        }

        final Path camel = in.resolve(".camel");
        final Path success = in.resolve(".success");
        final Path error = in.resolve(".error");

        await()
                .atMost(10, SECONDS)
                .until(() -> Stream.of(TEST_FILES).allMatch(f -> (Files.exists(camel.resolve(f)) ||
                        Files.exists(success.resolve(f))) &&
                        !Files.exists(in.resolve(f)) &&
                        !Files.exists(error.resolve(f))));
    }

    @Test
    public void shouldMoveFilesToErrorFolderWhenXMLValidationThrown(@TempDir Path in) throws IOException {

        for (final String fileName : INVALID_XML_TEST_FILES) {
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                Files.copy(is, in.resolve(fileName));
            }
        }

        final Path error = in.resolve(".error");

        await().atMost(10, SECONDS).until(() -> Stream.of(INVALID_XML_TEST_FILES).anyMatch(f -> Files.exists(error.resolve(f))));
    }


    @Test
    public void shouldMoveFilesToErrorFolderWhenFilenameIsInvalid(@TempDir Path in) throws IOException {

        for (final String fileName : INVALID_FILENAME_TEST_FILES) {
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                Files.copy(is, in.resolve(fileName));
            }
        }

        final Path error = in.resolve(".error");

        await().atMost(10, SECONDS).until(() -> Stream.of(INVALID_FILENAME_TEST_FILES).anyMatch(f -> Files.exists(error.resolve(f))));
    }

}
