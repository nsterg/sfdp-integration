package be.fgov.sfpd.integration.documents;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;

import java.io.*;
import java.util.Collections;
import java.util.Map;

/**
 * TODO keep track of processed files (in DB?)
 * TODO check where files need to go after being processed
 * TODO workflow search may not be complete
 * TODO add error handling
 * TODO split into smaller routes
 */
public class ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilder extends RouteBuilder {

    private static final Namespaces NS = new Namespaces("tns", "urn:document-schema");
    private static final Map<String, String> TASK_TO_WORKFLOW = Collections.singletonMap("PUBLIC_RETIREMENT_ESTIMATION", "Centestim");
    private static final String AUTHORIZATION = "Bearer {\"user\":\"_SYS_\"}";

    @Override
    public void configure() {
        from("{{camel.documents.input.uri}}?include=\\d{11}D\\d{6}T\\d{8}\\.xml")
            // validate XML
            .to("validator:be/fgov/sfpd/integration/documents/Document.xsd")
            // extract XML values into headers
            .setHeader("inss").xpath("/tns:Document/tns:NISS", String.class, NS)
            .setHeader("file").xpath("/tns:Document/tns:FileName", String.class, NS)
            .setHeader("task").xpath("/tns:Document/tns:ImportTask", String.class, NS)
            .setHeader("mime").xpath("/tns:Document/tns:MimeType", String.class, NS)
            // map the ImportTask to the workflow type
            .setHeader("type").exchange(exchange -> TASK_TO_WORKFLOW.get(exchange.getIn().getHeader("task", String.class)))
            // get workflows list
            .setHeader(Exchange.HTTP_METHOD).constant("GET")
            .setHeader("Authorization", simple(AUTHORIZATION))
            .toD("{{theseos.workflow.api}}?niss=${header.inss}&definition=${header.type}")
            .choice()
                .when(body().matches().jsonpath("$._embedded.workflows[?(@.length() > 0)]"))
                    // extract first workflow url into header
                    .setHeader("workflow").jsonpath("$._embedded.workflows[0]._links.self.href")
                    .log("found workflow ${header.workflow}")
                .otherwise()
                    .throwException(RuntimeException.class, "No workflow")
            .end()
            // get workflow
            .setHeader(Exchange.HTTP_METHOD).constant("GET")
            .setHeader("Authorization", simple(AUTHORIZATION))
            .toD("${header.workflow}")
            // extract upload url
            .setHeader("upload").jsonpath("$._forms.uploadDocument._links.target.href")
            .choice()
                // can't upload
                .when(header("upload").isNull())
                    .throwException(RuntimeException.class, "No uploadDocument transition")
                // upload
                .otherwise()
                    .log("upload document to ${header.upload}")
                    .pollEnrich().simple("{{camel.documents.input.uri}}?fileName=${header.file}")
                    .aggregationStrategy(this::aggregate)
                    .setHeader(Exchange.HTTP_METHOD).constant("POST")
                    .setHeader("Authorization", simple(AUTHORIZATION))
                    .toD("${header.upload}&throwExceptionOnFailure=false")
                    .validate(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(302))
            .end()
        ;
    }

    private Exchange aggregate(Exchange voucher, Exchange payload) {
        ContentType contentType = ContentType.create(voucher.getIn().getHeader("mime", String.class));
        HttpEntity resultEntity = MultipartEntityBuilder
            .create()
            .addTextBody("comment", "TODO")
            .addTextBody("uploadType", "NewDoc")
            .addBinaryBody("uploadedDocument", payload.getIn().getBody(InputStream.class), contentType, voucher.getIn().getHeader("file", String.class))
            .build();

        payload.getIn().setHeaders(voucher.getIn().getHeaders());
        payload.getIn().setHeader(Exchange.CONTENT_TYPE, resultEntity.getContentType().getValue());
        // payload.getIn().setBody(resultEntity); // TODO find a way to make it work
        // <workaround>
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resultEntity.writeTo(baos);
            payload.getIn().setBody(new ByteArrayInputStream(baos.toByteArray()));
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
        // </workaround>
        return payload;
    }
}
