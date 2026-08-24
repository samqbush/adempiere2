package org.adempiere.webservice.cxf;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.xml.namespace.QName;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.Provider;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.handler.MessageContext;

import org.adempiere.webservice.ServiceFault;
import org.adempiere.webservice.ServiceRequestContext;
import org.adempiere.webservice.SoapServiceDispatcher;
import org.compiere.Adempiere;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

abstract class CxfSoapProvider implements Provider<SOAPMessage> {

	private static final String SOAP12_NAMESPACE =
			"http://www.w3.org/2003/05/soap-envelope";

	private final String service;
	private final String namespace;
	private final Set<String> operations;
	private final SoapServiceDispatcher dispatcher;

	@Resource
	private WebServiceContext webServiceContext;

	CxfSoapProvider(
			String service,
			String namespace,
			Set<String> operations,
			SoapServiceDispatcher dispatcher) {
		this.service = Objects.requireNonNull(service, "service");
		this.namespace = Objects.requireNonNull(namespace, "namespace");
		this.operations = Set.copyOf(operations);
		this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
	}

	@Override
	public final SOAPMessage invoke(SOAPMessage request) {
		try {
			Element operationElement = requireOperation(request);
			String operation = operationElement.getLocalName();
			if (!operations.contains(operation)) {
				throw new ServiceFault(
						"UnknownOperation",
						"Unknown SOAP operation " + service + "." + operation,
						null);
			}
			if ("ExternalSales".equals(service)
					&& "uploadOrders".equals(operation)) {
				throw new ServiceFault(
						"Client",
						"Not enough message parts were received for the operation.",
						new QName(
								SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE,
								"Client",
								"soap"),
						null,
						null);
			}
			ensureApplicationStarted(operation);
			List<Object> arguments =
					SoapWireCodec.decodeArguments(service, operation, operationElement);
			Object result = dispatcher.invoke(
					service, operation, arguments, requestContext());
			return SoapWireCodec.encodeResponse(
					namespace, operation, result);
		} catch (ServiceFault fault) {
			return faultResponse(fault);
		} catch (Exception failure) {
			return faultResponse(new ServiceFault(
					"Client",
					failure.getMessage() == null
							? failure.getClass().getName()
							: failure.getMessage(),
					new QName(
							SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE,
							"Client",
							"soap"),
					null,
					failure));
		}
	}

	private void ensureApplicationStarted(String operation) throws ServiceFault {
		if ("ADService".equals(service)
				&& ("getVersion".equals(operation)
						|| "isLoggedIn".equals(operation))) {
			return;
		}
		if (!Adempiere.startup(false)) {
			throw new ServiceFault(
					"Server",
					"ADempiere application startup failed",
					null);
		}
	}

	private Element requireOperation(SOAPMessage message) throws Exception {
		Node operation = message.getSOAPBody().getFirstChild();
		while (operation != null && operation.getNodeType() != Node.ELEMENT_NODE) {
			operation = operation.getNextSibling();
		}
		if (!(operation instanceof Element)
				|| nextElement(operation.getNextSibling()) != null) {
			throw new ServiceFault(
					"MalformedRequest",
					"SOAP body must contain exactly one operation",
					null);
		}
		Element element = (Element) operation;
		if (!namespace.equals(element.getNamespaceURI())
				|| element.getLocalName() == null) {
			throw new ServiceFault(
					"UnknownOperation",
					"SOAP operation namespace does not match " + service,
					null);
		}
		return element;
	}

	private static Node nextElement(Node node) {
		Node current = node;
		while (current != null && current.getNodeType() != Node.ELEMENT_NODE) {
			current = current.getNextSibling();
		}
		return current;
	}

	private ServiceRequestContext requestContext() throws ServiceFault {
		if (webServiceContext == null) {
			throw new ServiceFault(
					"Server",
					"Web service request context is unavailable",
					null);
		}
		Object request = webServiceContext.getMessageContext().get(
				MessageContext.SERVLET_REQUEST);
		if (!(request instanceof HttpServletRequest)) {
			throw new ServiceFault(
					"Server",
					"HTTP request context is unavailable",
					null);
		}
		return new HttpSessionServiceRequestContext(
				((HttpServletRequest) request).getSession(true));
	}

	private SOAPMessage faultResponse(ServiceFault serviceFault) {
		try {
			if (webServiceContext != null) {
				webServiceContext.getMessageContext().put(
						MessageContext.HTTP_RESPONSE_CODE,
						Integer.valueOf(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
			}
			SOAPMessage message = MessageFactory.newInstance(
					SOAPConstants.SOAP_1_1_PROTOCOL).createMessage();
			message.getSOAPPart().getEnvelope().setPrefix("soap");
			message.getSOAPPart().getEnvelope().getBody().setPrefix("soap");
			message.getSOAPPart().getEnvelope().getHeader().detachNode();
			message.getSOAPPart().getEnvelope().removeNamespaceDeclaration(
					"SOAP-ENV");
			message.getSOAPPart().getEnvelope().addNamespaceDeclaration(
					"soap", SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE);
			message.getSOAPPart().getEnvelope().addNamespaceDeclaration(
					"xsd", "http://www.w3.org/2001/XMLSchema");
			message.getSOAPPart().getEnvelope().addNamespaceDeclaration(
					"xsi", "http://www.w3.org/2001/XMLSchema-instance");
			QName code = serviceFault.getFaultCode();
			String wireCode;
			if (SOAP12_NAMESPACE.equals(code.getNamespaceURI())
					&& "Receiver".equals(code.getLocalPart())) {
				wireCode = "soap:Server";
			} else if (SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE.equals(
					code.getNamespaceURI())) {
				wireCode = "soap:" + code.getLocalPart();
			} else {
				wireCode = code.getLocalPart();
			}
			SOAPBodyElement fault = message.getSOAPBody().addBodyElement(
					new QName(
							SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE,
							"Fault",
							"soap"));
			fault.addChildElement("faultcode").addTextNode(wireCode);
			fault.addChildElement("faultstring").addTextNode(
					serviceFault.getMessage());
			if (serviceFault.getDetail() != null) {
				SOAPElement detail = fault.addChildElement("detail");
				detail.addTextNode(serviceFault.getDetail());
			}
			message.saveChanges();
			return message;
		} catch (Exception failure) {
			throw new IllegalStateException(
					"Unable to create SOAP fault for "
							+ serviceFault.getCode(),
					failure);
		}
	}
}
