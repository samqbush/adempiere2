package org.adempiere.webservice.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.adempiere.webservice.ServiceFault;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openbravo.erpCommon.ws.externalSales.GetCustomersResponseDocument;

@Tag("UnitTest")
class DefaultCustomerServiceTest {

	@Test
	void buildsTheCanonicalXmlBeansResponseAndAuthenticatesTheOperation()
			throws Exception {
		RecordingAuthenticationService authentication =
				new RecordingAuthenticationService();
		DefaultCustomerService service =
				new DefaultCustomerService(authentication);

		GetCustomersResponseDocument response =
				service.getCustomers(11, "user", "password");

		assertNotNull(response.getGetCustomersResponse());
		assertEquals("user", authentication.username);
		assertEquals("password", authentication.password);
		assertEquals("WebService", authentication.service);
		assertEquals("getCustomers", authentication.operation);
	}

	@Test
	void propagatesNeutralBusinessFaultsWithoutTransportCoupling() {
		ServiceFault expected =
				new ServiceFault("AUTHENTICATION", "Denied", null);
		PosAuthenticationService authentication =
				(username, password, service, operation) -> {
					throw expected;
				};
		DefaultCustomerService service =
				new DefaultCustomerService(authentication);

		ServiceFault actual = assertThrows(
				ServiceFault.class,
				() -> service.getCustomers(11, "user", "password"));

		assertSame(expected, actual);
	}

	private static final class RecordingAuthenticationService
			implements PosAuthenticationService {

		private String username;
		private String password;
		private String service;
		private String operation;

		@Override
		public void authenticate(
				String username,
				String password,
				String service,
				String operation) {
			this.username = username;
			this.password = password;
			this.service = service;
			this.operation = operation;
		}
	}
}
