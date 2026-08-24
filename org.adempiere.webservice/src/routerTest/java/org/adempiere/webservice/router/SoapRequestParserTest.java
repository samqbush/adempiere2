package org.adempiere.webservice.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.adempiere.webservice.SoapOperationRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class SoapRequestParserTest {

	@Test
	void acceptsOnlyAnInventoriedServiceOperationAndNamespace() {
		assertEquals(
				"getVersion",
				SoapRequestParser.parse(
						"ADService",
						envelope(
								"http://3e.pl/ADInterface",
								"getVersion"),
						SoapOperationRegistry.requiredOperationKeys()));
	}

	@Test
	void rejectsUnknownOperations() {
		assertThrows(
				IllegalArgumentException.class,
				() -> SoapRequestParser.parse(
						"ADService",
						envelope(
								"http://3e.pl/ADInterface",
								"unknown"),
						SoapOperationRegistry.requiredOperationKeys()));
	}

	@Test
	void rejectsAServiceNamespaceMismatch() {
		assertThrows(
				IllegalArgumentException.class,
				() -> SoapRequestParser.parse(
						"WebService",
						envelope(
								"http://3e.pl/ADInterface",
								"getCustomers"),
						SoapOperationRegistry.requiredOperationKeys()));
	}

	@Test
	void rejectsMultipleOperations() {
		String xml = "<soap:Envelope xmlns:soap=\""
				+ "http://schemas.xmlsoap.org/soap/envelope/\" "
				+ "xmlns:ad=\"http://3e.pl/ADInterface\">"
				+ "<soap:Body><ad:getVersion/><ad:isLoggedIn/>"
				+ "</soap:Body></soap:Envelope>";
		assertThrows(
				IllegalArgumentException.class,
				() -> SoapRequestParser.parse(
						"ADService",
						xml.getBytes(StandardCharsets.UTF_8),
						SoapOperationRegistry.requiredOperationKeys()));
	}

	@Test
	void rejectsDocumentTypeDeclarations() {
		String xml = "<!DOCTYPE soap:Envelope ["
				+ "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
				+ new String(
						envelope(
								"http://3e.pl/ADInterface",
								"getVersion"),
						StandardCharsets.UTF_8);
		assertThrows(
				IllegalArgumentException.class,
				() -> SoapRequestParser.parse(
						"ADService",
						xml.getBytes(StandardCharsets.UTF_8),
						SoapOperationRegistry.requiredOperationKeys()));
	}

	private static byte[] envelope(String namespace, String operation) {
		String xml = "<soap:Envelope xmlns:soap=\""
				+ "http://schemas.xmlsoap.org/soap/envelope/\" "
				+ "xmlns:svc=\"" + namespace + "\">"
				+ "<soap:Body><svc:" + operation + "/></soap:Body>"
				+ "</soap:Envelope>";
		return xml.getBytes(StandardCharsets.UTF_8);
	}
}
