package org.adempiere.webservice.cxf;

import java.util.Set;

import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;

import org.adempiere.webservice.SoapServiceDispatcher;

@WebServiceProvider(
		serviceName = "WebService",
		portName = "WebServiceHttpPort",
		targetNamespace = CustomerWebServiceProvider.NAMESPACE)
@ServiceMode(Service.Mode.MESSAGE)
final class CustomerWebServiceProvider extends CxfSoapProvider {

	static final String NAMESPACE =
			"http://externalSales.ws.erpCommon.openbravo.org";

	CustomerWebServiceProvider(SoapServiceDispatcher dispatcher) {
		super(
				"WebService",
				NAMESPACE,
				Set.of("getCustomers"),
				dispatcher);
	}
}
