package org.adempiere.webservice;

import java.util.List;

public interface SoapServiceDispatcher {

	Object invoke(
			String service,
			String operation,
			List<?> arguments,
			ServiceRequestContext context)
			throws ServiceFault;
}
