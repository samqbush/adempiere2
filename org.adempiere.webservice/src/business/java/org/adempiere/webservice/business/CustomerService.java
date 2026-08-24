package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceFault;
import org.openbravo.erpCommon.ws.externalSales.GetCustomersResponseDocument;

public interface CustomerService {

	GetCustomersResponseDocument getCustomers(
			int clientId,
			String username,
			String password)
			throws ServiceFault;
}
