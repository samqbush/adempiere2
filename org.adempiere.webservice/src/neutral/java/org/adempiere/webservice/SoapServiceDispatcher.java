package org.adempiere.webservice;

import org.apache.xmlbeans.XmlObject;

public interface SoapServiceDispatcher {

	XmlObject invoke(
			String service,
			String operation,
			XmlObject request,
			ServiceRequestContext context)
			throws ServiceFault;
}
