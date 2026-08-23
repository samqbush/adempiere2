package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceFault;

public interface PosAuthenticationService {

	void authenticate(
			String username,
			String password,
			String service,
			String operation)
			throws ServiceFault;
}
