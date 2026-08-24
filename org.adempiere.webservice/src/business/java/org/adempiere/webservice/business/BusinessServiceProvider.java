package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceRequestContext;

public interface BusinessServiceProvider {

	ADServiceBusiness getADService(ServiceRequestContext context);

	ModelADServiceBusiness getModelADService();

	ExternalSalesService getExternalSalesService();

	CustomerService getCustomerService();
}
