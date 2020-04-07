package be.fgov.sfpd.integration.documents;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.Collections;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.xml.sax.SAXParseException;

/**
 * TODO keep track of processed files (in DB?)
 * TODO check where files need to go after being processed
 * TODO workflow search may not be complete
 * TODO add error handling
 * TODO split into smaller routes
 */
public class ImportDocumentFromAPSoftToTheseosWorkflowRouteBuilder extends RouteBuilder {

    private static final String VALID_FILENAME_REGEX = "\\d{11}D\\d{6}T\\d{8}\\.xml";
	private static final String WORKFLOW_DETAILS_DIRECT_URI = "direct:getWorkflow";
	private static final String WORKFLOWS_FROM_THESEOS_DIRECT_URI = "direct:workflowResults";
	private static final String WORKFLOW_HREF_URI = "${header.workflow}";
	private static final String EMBEDDED_WORKFLOWS_LINKS_PATH = "$._embedded.workflows[0]._links.self.href";
	private static final String CAMEL_DOCUMENTS_INPUT_URI = "{{camel.documents.input.uri}}?fileName=${header.file}";
	private static final String HEADER_UPLOAD_URI = "${header.upload}&throwExceptionOnFailure=false";
	private static final String UPLOAD_DOC_TARGET_URL = "$._forms.uploadDocument._links.target.href";
	private static final String THESEOS_WORKFLOW_API_URI = "{{theseos.workflow.api}}?niss=${header.inss}&definition=${header.type}";
	private static final String XSD_VALIDATION_URI = "validator:be/fgov/sfpd/integration/documents/Document.xsd";
	private static final String INPUT_URI = "{{camel.documents.input.uri}}?include=.*\\.xml&move=.success&moveFailed=.error";
    private static final Map<String, String> TASK_TO_WORKFLOW = Collections.singletonMap("PUBLIC_RETIREMENT_ESTIMATION", "Centestim");
    private static final String AUTHORIZATION = "Bearer {\"user\":\"_SYS_\"}";
	private static final Namespaces NS = new Namespaces("tns", "urn:document-schema");

    @Override
    public void configure() {

    	onException(SAXParseException.class)
        	.log("Failed to validate input XML file ${header.CamelFileName}");
    	onException(ConnectException.class)
    		.log("Failed to connect to Theseos workfile API while processing file ${header.CamelFileName}");
    	onException(Exception.class)
        	.log("Some error occurred while processing file ${header.CamelFileName}");

        from(INPUT_URI)
            .process(validateFilename())
	        .to(XSD_VALIDATION_URI)
	        .process(extractXMLValues())
	        .process(mapImportTaskToWorkflowType())
	        .setHeader("Authorization", simple(AUTHORIZATION))
	        .process(prepareForHttpGetRequest())
	        .toD(THESEOS_WORKFLOW_API_URI)
	        .to(WORKFLOWS_FROM_THESEOS_DIRECT_URI);

        from(WORKFLOWS_FROM_THESEOS_DIRECT_URI)
	        .choice()
	            .when(hasEmbeddedWorkflows())
	                // extract first workflow url into header
	                .setHeader("workflow").jsonpath(EMBEDDED_WORKFLOWS_LINKS_PATH)
	                .log("found workflow ${header.workflow}")
	            .otherwise()
	                .throwException(RuntimeException.class, "No workflow")
	        .end()
	        .toD(WORKFLOW_HREF_URI)
	        .to(WORKFLOW_DETAILS_DIRECT_URI);

        from(WORKFLOW_DETAILS_DIRECT_URI)
	        .setHeader("upload").jsonpath(UPLOAD_DOC_TARGET_URL)
	        .choice()
	            .when(cantUpload())
	                .throwException(RuntimeException.class, "No uploadDocument transition")
	            .otherwise()
	                .log("upload document to ${header.upload}")
	                .pollEnrich().simple(CAMEL_DOCUMENTS_INPUT_URI)
	                .aggregationStrategy(this::aggregate)
	    	        .process(prepareForHttpPostRequest())
	                .toD(HEADER_UPLOAD_URI)
	                .validate(isResponse302())
			.end();
    }

	private Processor validateFilename() {
		return (exchange) -> {

			final String filename = exchange.getIn().getHeader(Exchange.FILE_NAME, String.class);
			if (!filename.matches(VALID_FILENAME_REGEX)) {
				throw new RuntimeException("Invalid file found in input. Filename was: " + filename
						+ ". Expected file format is: " + VALID_FILENAME_REGEX);
			}
		};
	}


	private Predicate isResponse302() {
		return header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(302);
	}

	private Predicate cantUpload() {
		return header("upload").isNull();
	}

	private Predicate hasEmbeddedWorkflows() {
		return body().matches().jsonpath("$._embedded.workflows[?(@.length() > 0)]");
	}

	private Processor extractXMLValues() {
		return (exchange) -> {
			final String inss = (String) NS.xpath("/tns:Document/tns:NISS", String.class).evaluate(exchange);
			final String file = (String) NS.xpath("/tns:Document/tns:FileName", String.class).evaluate(exchange);
			final String task = (String) NS.xpath("/tns:Document/tns:ImportTask", String.class).evaluate(exchange);
			final String mime = (String) NS.xpath("/tns:Document/tns:MimeType", String.class).evaluate(exchange);

			final Message in = exchange.getIn();

			in.setHeader("inss", inss);
			in.setHeader("file", file);
			in.setHeader("task", task);
			in.setHeader("mime", mime);
		};
	}

	private Processor mapImportTaskToWorkflowType() {
		return (exchange) -> {
			final String type = TASK_TO_WORKFLOW.get(exchange.getIn().getHeader("task", String.class));

			exchange.getIn().setHeader("type", type);
		};
	}

	private Processor prepareForHttpGetRequest() {
		return (exchange) -> {
			exchange.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
		};
	}

	private Processor prepareForHttpPostRequest() {
		return (exchange) -> {
			exchange.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
		};
	}

	private Exchange aggregate(Exchange voucher, Exchange payload) {
        final ContentType contentType = ContentType.create(voucher.getIn().getHeader("mime", String.class));
        final HttpEntity resultEntity = MultipartEntityBuilder
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
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resultEntity.writeTo(baos);
            payload.getIn().setBody(new ByteArrayInputStream(baos.toByteArray()));
        } catch (final IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
        // </workaround>
        return payload;
    }
}
