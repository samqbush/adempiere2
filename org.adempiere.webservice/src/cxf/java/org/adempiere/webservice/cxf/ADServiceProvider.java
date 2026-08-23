package org.adempiere.webservice.cxf;

import java.util.Set;

import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;

import org.adempiere.webservice.SoapServiceDispatcher;

@WebServiceProvider(
		serviceName = "ADService",
		portName = "ADServiceHttpPort",
		targetNamespace = ADServiceProvider.NAMESPACE)
@ServiceMode(Service.Mode.MESSAGE)
final class ADServiceProvider extends CxfSoapProvider {

	static final String NAMESPACE = "http://3e.pl/ADInterface";

	ADServiceProvider(SoapServiceDispatcher dispatcher) {
		super(
				"ADService",
				NAMESPACE,
				Set.of(
						"getVersion",
						"getLookupSearchData",
						"getLocation",
						"runProcess",
						"getADMenu",
						"getDataRow",
						"saveLocation",
						"login",
						"getADWindow",
						"getLookupData",
						"getDocAction",
						"setDocAction",
						"getWindowTabData",
						"refreshDataRow",
						"deleteDataRow",
						"addNewDataRow",
						"isLoggedIn",
						"ignoreDataRow",
						"saveDataRow",
						"updateDataRow",
						"getProcessParams"),
				dispatcher);
	}
}
