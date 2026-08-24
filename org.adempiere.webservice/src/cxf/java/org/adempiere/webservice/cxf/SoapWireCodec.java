package org.adempiere.webservice.cxf;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBody;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPEnvelope;
import jakarta.xml.soap.SOAPMessage;

import org.adempiere.webservice.ServiceFault;
import org.apache.xmlbeans.XmlObject;
import org.openbravo.erpCommon.ws.externalSales.ArrayOfTns1Order;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import pl.x3E.adInterface.ADLoginRequestDocument;
import pl.x3E.adInterface.GetLookupSearchDataReqDocument;
import pl.x3E.adInterface.GetProcessParamsDocument;
import pl.x3E.adInterface.LocationDocument;
import pl.x3E.adInterface.ModelCRUDRequestDocument;
import pl.x3E.adInterface.ModelGetListRequestDocument;
import pl.x3E.adInterface.ModelRunProcessRequestDocument;
import pl.x3E.adInterface.ModelSetDocActionRequestDocument;
import pl.x3E.adInterface.RunProcessDocument;
import pl.x3E.adInterface.WindowTabDataDocument;
import pl.x3E.adInterface.WindowTabDataReqDocument;

final class SoapWireCodec {

	private SoapWireCodec() {
	}

	static List<Object> decodeArguments(
			String service,
			String operation,
			Element operationElement) throws Exception {
		List<Element> parts = childElements(operationElement);
		List<Object> arguments = new ArrayList<Object>(parts.size());
		String key = service + "." + operation;
		switch (key) {
			case "ADService.getVersion":
			case "ADService.isLoggedIn":
				requirePartCount(key, parts, 0);
				break;
			case "ADService.getLookupSearchData":
				arguments.add(GetLookupSearchDataReqDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.getLocation":
			case "ADService.saveLocation":
				arguments.add(LocationDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.runProcess":
				arguments.add(RunProcessDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.login":
				arguments.add(ADLoginRequestDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.getWindowTabData":
				arguments.add(WindowTabDataReqDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.getProcessParams":
				arguments.add(GetProcessParamsDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ModelADService.setDocAction":
				arguments.add(ModelSetDocActionRequestDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ModelADService.createData":
			case "ModelADService.deleteData":
			case "ModelADService.readData":
			case "ModelADService.updateData":
			case "ModelADService.queryData":
				arguments.add(ModelCRUDRequestDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ModelADService.getList":
				arguments.add(ModelGetListRequestDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ModelADService.runProcess":
				arguments.add(ModelRunProcessRequestDocument.Factory.parse(
						onlyPart(key, parts)));
				break;
			case "ADService.saveDataRow":
			case "ADService.updateDataRow":
				requirePartCount(key, parts, 4);
				addIntegers(arguments, parts, 0, 3, key);
				arguments.add(WindowTabDataDocument.Factory.parse(parts.get(3)));
				break;
			case "ExternalSales.uploadOrders":
				requirePartCount(key, parts, 6);
				addIntegers(arguments, parts, 0, 3, key);
				arguments.add(ArrayOfTns1Order.Factory.parse(parts.get(3)));
				arguments.add(text(parts.get(4)));
				arguments.add(text(parts.get(5)));
				break;
			default:
				decodeScalarArguments(key, parts, arguments);
		}
		return List.copyOf(arguments);
	}

	static SOAPMessage encodeResponse(
			String namespace,
			String operation,
			Object result) throws Exception {
		SOAPMessage message = MessageFactory.newInstance(
				SOAPConstants.SOAP_1_1_PROTOCOL).createMessage();
		SOAPEnvelope envelope = message.getSOAPPart().getEnvelope();
		envelope.setPrefix("soap");
		envelope.getBody().setPrefix("soap");
		envelope.getHeader().detachNode();
		envelope.removeNamespaceDeclaration("SOAP-ENV");
		envelope.addNamespaceDeclaration(
				"soap", SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE);
		envelope.addNamespaceDeclaration(
				"xsd", "http://www.w3.org/2001/XMLSchema");
		envelope.addNamespaceDeclaration(
				"xsi", "http://www.w3.org/2001/XMLSchema-instance");
		SOAPBody body = envelope.getBody();
		SOAPElement response = body.addBodyElement(
				new QName(namespace, operation + "Response", "ns1"));
		if (result instanceof XmlObject) {
			Document document = (Document) ((XmlObject) result).newDomNode();
			Node root = document.getDocumentElement();
			Node imported = response.getOwnerDocument().importNode(root, true);
			useDefaultResultNamespace(imported, namespace, true);
			response.appendChild(imported);
		} else if (result != null) {
			SOAPElement out = response.addChildElement(
					new QName("", "out"));
			out.addNamespaceDeclaration("", "");
			out.addTextNode(String.valueOf(result));
		}
		message.saveChanges();
		return message;
	}

	private static void useDefaultResultNamespace(
			Node node,
			String namespace,
			boolean root) {
		if (node.getNodeType() == Node.ELEMENT_NODE
				&& namespace.equals(node.getNamespaceURI())) {
			node.setPrefix(null);
			if (root) {
				((Element) node).setAttributeNS(
						"http://www.w3.org/2000/xmlns/",
						"xmlns",
						namespace);
			}
			((Element) node).removeAttributeNS(
					"http://www.w3.org/2000/xmlns/",
					"adin");
		}
		for (Node child = node.getFirstChild();
				child != null;
				child = child.getNextSibling()) {
			useDefaultResultNamespace(child, namespace, false);
		}
	}

	private static void decodeScalarArguments(
			String key,
			List<Element> parts,
			List<Object> arguments) throws ServiceFault {
		switch (key) {
			case "ADService.getADMenu":
				requirePartCount(key, parts, 1);
				addIntegers(arguments, parts, 0, 1, key);
				return;
			case "ADService.addNewDataRow":
				requirePartCount(key, parts, 2);
				addIntegers(arguments, parts, 0, 2, key);
				return;
			case "ADService.getDataRow":
			case "ADService.getADWindow":
			case "ADService.refreshDataRow":
			case "ADService.deleteDataRow":
			case "ADService.ignoreDataRow":
				requirePartCount(key, parts, 3);
				addIntegers(arguments, parts, 0, 3, key);
				return;
			case "ADService.getLookupData":
			case "ADService.getDocAction":
				requirePartCount(key, parts, 4);
				addIntegers(arguments, parts, 0, 3, key);
				arguments.add(text(parts.get(3)));
				return;
			case "ADService.setDocAction":
				requirePartCount(key, parts, 5);
				addIntegers(arguments, parts, 0, 3, key);
				arguments.add(text(parts.get(3)));
				arguments.add(text(parts.get(4)));
				return;
			case "ExternalSales.getProductsPlusCatalog":
			case "ExternalSales.getProductsCatalog":
				requirePartCount(key, parts, 5);
				addIntegers(arguments, parts, 0, 3, key);
				arguments.add(text(parts.get(3)));
				arguments.add(text(parts.get(4)));
				return;
			case "WebService.getCustomers":
				requirePartCount(key, parts, 3);
				addIntegers(arguments, parts, 0, 1, key);
				arguments.add(text(parts.get(1)));
				arguments.add(text(parts.get(2)));
				return;
			default:
				throw new ServiceFault(
						"UnknownOperation",
						"No SOAP argument codec for " + key,
						null);
		}
	}

	private static void addIntegers(
			List<Object> arguments,
			List<Element> parts,
			int start,
			int end,
			String key) throws ServiceFault {
		for (int index = start; index < end; index++) {
			try {
				arguments.add(Integer.valueOf(text(parts.get(index))));
			} catch (NumberFormatException failure) {
				throw new ServiceFault(
						"MalformedRequest",
						"SOAP argument " + index + " for " + key
								+ " must be an integer",
						null,
						failure);
			}
		}
	}

	private static Element onlyPart(String key, List<Element> parts)
			throws ServiceFault {
		requirePartCount(key, parts, 1);
		return parts.get(0);
	}

	private static void requirePartCount(
			String key,
			List<Element> parts,
			int expected) throws ServiceFault {
		if (parts.size() != expected) {
			throw new ServiceFault(
					"MalformedRequest",
					"SOAP operation " + key + " expected " + expected
							+ " arguments but received " + parts.size(),
					null);
		}
	}

	private static List<Element> childElements(Element parent) {
		List<Element> elements = new ArrayList<Element>();
		Node child = parent.getFirstChild();
		while (child != null) {
			if (child instanceof Element) {
				elements.add((Element) child);
			}
			child = child.getNextSibling();
		}
		return elements;
	}

	private static String text(Element part) {
		return part.getTextContent();
	}
}
