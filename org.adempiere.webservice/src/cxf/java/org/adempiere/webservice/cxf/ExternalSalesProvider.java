package org.adempiere.webservice.cxf;

import java.util.Set;

import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;

import org.adempiere.webservice.SoapServiceDispatcher;

@WebServiceProvider(
		serviceName = "ExternalSales",
		portName = "ExternalSalesHttpPort",
		targetNamespace = ExternalSalesProvider.NAMESPACE)
@ServiceMode(Service.Mode.MESSAGE)
final class ExternalSalesProvider extends CxfSoapProvider {

	static final String NAMESPACE =
			"http://externalSales.ws.erpCommon.openbravo.org";

	ExternalSalesProvider(SoapServiceDispatcher dispatcher) {
		super(
				"ExternalSales",
				NAMESPACE,
				Set.of(
						"getProductsPlusCatalog",
						"getProductsCatalog",
						"uploadOrders"),
				dispatcher);
	}
}
