package org.adempiere.webservice.business;

import java.util.Objects;

import org.adempiere.webservice.ServiceFault;
import org.openbravo.erpCommon.ws.externalSales.ArrayOfTns1Order;
import org.openbravo.erpCommon.ws.externalSales.ProductsCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.ProductsPlusCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.UploadOrdersResponseDocument;

public final class DefaultExternalSalesService
		implements ExternalSalesService {

	private static final String SERVICE = "ExternalSales";

	private final PosAuthenticationService authenticationService;

	public DefaultExternalSalesService() {
		this(new DatabasePosAuthenticationService());
	}

	public DefaultExternalSalesService(
			PosAuthenticationService authenticationService) {
		this.authenticationService =
				Objects.requireNonNull(authenticationService, "authenticationService");
	}

	@Override
	public UploadOrdersResponseDocument uploadOrders(
			int entityId,
			int organizationId,
			int salesChannel,
			ArrayOfTns1Order newOrders,
			String username,
			String password)
			throws ServiceFault {
		UploadOrdersResponseDocument response =
				UploadOrdersResponseDocument.Factory.newInstance();
		response.addNewUploadOrdersResponse();

		authenticationService.authenticate(
				username, password, SERVICE, "uploadOrders");
		return response;
	}

	@Override
	public ProductsCatalogResponseDocument getProductsCatalog(
			int entityId,
			int organizationId,
			int salesChannel,
			String username,
			String password)
			throws ServiceFault {
		ProductsCatalogResponseDocument response =
				ProductsCatalogResponseDocument.Factory.newInstance();
		response.addNewProductsCatalogResponse();

		authenticationService.authenticate(
				username, password, SERVICE, "getProductsCatalog");
		return response;
	}

	@Override
	public ProductsPlusCatalogResponseDocument getProductsPlusCatalog(
			int entityId,
			int organizationId,
			int salesChannel,
			String username,
			String password)
			throws ServiceFault {
		ProductsPlusCatalogResponseDocument response =
				ProductsPlusCatalogResponseDocument.Factory.newInstance();
		response.addNewProductsPlusCatalogResponse();

		authenticationService.authenticate(
				username, password, SERVICE, "getProductsPlusCatalog");
		return response;
	}
}
