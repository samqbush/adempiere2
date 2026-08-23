package org.adempiere.webservice.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openbravo.erpCommon.ws.externalSales.ProductsCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.ProductsPlusCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.UploadOrdersResponseDocument;

@Tag("UnitTest")
class DefaultExternalSalesServiceTest {

	@Test
	void buildsCanonicalResponsesAndAuthenticatesEveryPublishedOperation()
			throws Exception {
		RecordingAuthenticationService authentication =
				new RecordingAuthenticationService();
		DefaultExternalSalesService service =
				new DefaultExternalSalesService(authentication);

		ProductsCatalogResponseDocument catalog = service.getProductsCatalog(
				11, 11, 0, "user", "password");
		ProductsPlusCatalogResponseDocument plusCatalog =
				service.getProductsPlusCatalog(
						11, 11, 0, "user", "password");
		UploadOrdersResponseDocument upload = service.uploadOrders(
				11, 11, 0, null, "user", "password");

		assertNotNull(catalog.getProductsCatalogResponse());
		assertNotNull(plusCatalog.getProductsPlusCatalogResponse());
		assertNotNull(upload.getUploadOrdersResponse());
		assertEquals(
				List.of(
						"ExternalSales.getProductsCatalog",
						"ExternalSales.getProductsPlusCatalog",
						"ExternalSales.uploadOrders"),
				authentication.operations);
	}

	private static final class RecordingAuthenticationService
			implements PosAuthenticationService {

		private final List<String> operations = new ArrayList<>();

		@Override
		public void authenticate(
				String username,
				String password,
				String service,
				String operation) {
			operations.add(service + "." + operation);
		}
	}
}
