package org.adempiere.webservice.router;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.adempiere.webservice.ServiceOperationKey;

final class SoapRequestParser {

	private static final String SOAP_NAMESPACE =
			"http://schemas.xmlsoap.org/soap/envelope/";
	private static final Map<String, String> SERVICE_NAMESPACES =
			serviceNamespaces();

	private SoapRequestParser() {
	}

	static String parse(
			String service,
			byte[] body,
			Set<ServiceOperationKey> operations) {
		try {
			XMLInputFactory factory = XMLInputFactory.newInstance();
			factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
			factory.setProperty(
					XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,
					Boolean.FALSE);
			factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
				throw new IllegalArgumentException(
						"External XML entities are not allowed");
			});
			XMLStreamReader reader = factory.createXMLStreamReader(
					new ByteArrayInputStream(body));
			String operationName = null;
			String operationNamespace = null;
			int depth = 0;
			boolean bodySeen = false;
			boolean insideBody = false;
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.DTD
						|| event == XMLStreamConstants.ENTITY_REFERENCE) {
					throw new IllegalArgumentException(
							"DTD and entity declarations are not allowed");
				}
				if (event == XMLStreamConstants.START_ELEMENT) {
					depth++;
					if (depth == 1
							&& (!SOAP_NAMESPACE.equals(reader.getNamespaceURI())
									|| !"Envelope".equals(
											reader.getLocalName()))) {
						throw new IllegalArgumentException(
								"Request must contain a SOAP 1.1 envelope");
					}
					if (depth == 2
							&& SOAP_NAMESPACE.equals(reader.getNamespaceURI())
							&& "Body".equals(reader.getLocalName())) {
						if (bodySeen) {
							throw new IllegalArgumentException(
									"SOAP envelope contains multiple Body elements");
						}
						bodySeen = true;
						insideBody = true;
					} else if (depth == 3 && insideBody) {
						if (operationName != null) {
							throw new IllegalArgumentException(
									"SOAP body must contain exactly one operation");
						}
						operationName = reader.getLocalName();
						operationNamespace = reader.getNamespaceURI();
					}
				} else if (event == XMLStreamConstants.END_ELEMENT) {
					if (depth == 2
							&& SOAP_NAMESPACE.equals(reader.getNamespaceURI())
							&& "Body".equals(reader.getLocalName())) {
						insideBody = false;
					}
					depth--;
				}
			}
			reader.close();
			if (!bodySeen || operationName == null) {
				throw new IllegalArgumentException(
						"SOAP body must contain exactly one operation");
			}
			String expectedNamespace = SERVICE_NAMESPACES.get(service);
			if (expectedNamespace == null
					|| !expectedNamespace.equals(operationNamespace)) {
				throw new IllegalArgumentException(
						"SOAP operation namespace does not match " + service);
			}
			if (!operations.contains(
					new ServiceOperationKey(service, operationName))) {
				throw new IllegalArgumentException(
						"Unknown SOAP operation "
								+ service + "." + operationName);
			}
			return operationName;
		} catch (IllegalArgumentException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new IllegalArgumentException(
					"Malformed SOAP request",
					failure);
		}
	}

	private static Map<String, String> serviceNamespaces() {
		Map<String, String> namespaces = new HashMap<String, String>();
		namespaces.put("ADService", "http://3e.pl/ADInterface");
		namespaces.put("ModelADService", "http://3e.pl/ADInterface");
		namespaces.put(
				"ExternalSales",
				"http://externalSales.ws.erpCommon.openbravo.org");
		namespaces.put(
				"WebService",
				"http://externalSales.ws.erpCommon.openbravo.org");
		return namespaces;
	}
}
