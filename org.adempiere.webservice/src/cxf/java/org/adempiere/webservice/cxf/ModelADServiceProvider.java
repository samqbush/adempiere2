package org.adempiere.webservice.cxf;

import java.util.Set;

import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;

import org.adempiere.webservice.SoapServiceDispatcher;

@WebServiceProvider(
		serviceName = "ModelADService",
		portName = "ModelADServiceHttpPort",
		targetNamespace = ModelADServiceProvider.NAMESPACE)
@ServiceMode(Service.Mode.MESSAGE)
final class ModelADServiceProvider extends CxfSoapProvider {

	static final String NAMESPACE = "http://3e.pl/ADInterface";

	ModelADServiceProvider(SoapServiceDispatcher dispatcher) {
		super(
				"ModelADService",
				NAMESPACE,
				Set.of(
						"setDocAction",
						"createData",
						"deleteData",
						"readData",
						"getList",
						"runProcess",
						"updateData",
						"queryData"),
				dispatcher);
	}
}
