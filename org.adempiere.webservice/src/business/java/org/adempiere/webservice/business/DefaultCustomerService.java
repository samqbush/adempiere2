package org.adempiere.webservice.business;

import java.util.Objects;

import org.adempiere.webservice.ServiceFault;
import org.openbravo.erpCommon.ws.externalSales.GetCustomersResponseDocument;

public final class DefaultCustomerService implements CustomerService {

	private static final String SERVICE = "WebService";
	private static final String OPERATION = "getCustomers";

	private final PosAuthenticationService authenticationService;

	public DefaultCustomerService() {
		this(new DatabasePosAuthenticationService());
	}

	public DefaultCustomerService(
			PosAuthenticationService authenticationService) {
		this.authenticationService =
				Objects.requireNonNull(authenticationService, "authenticationService");
	}

	@Override
	public GetCustomersResponseDocument getCustomers(
			int clientId,
			String username,
			String password)
			throws ServiceFault {
		GetCustomersResponseDocument response =
				GetCustomersResponseDocument.Factory.newInstance();
		response.addNewGetCustomersResponse();

		authenticationService.authenticate(
				username, password, SERVICE, OPERATION);
		return response;
	}
}
