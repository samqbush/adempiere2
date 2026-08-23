package org.adempiere.webservice;

import java.util.List;

@FunctionalInterface
public interface SoapOperationHandler {

	Object invoke(List<?> arguments, ServiceRequestContext context)
			throws ServiceFault;
}
