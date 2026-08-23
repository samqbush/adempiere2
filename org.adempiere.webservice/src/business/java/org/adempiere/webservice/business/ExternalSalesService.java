package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceFault;
import org.openbravo.erpCommon.ws.externalSales.ArrayOfTns1Order;
import org.openbravo.erpCommon.ws.externalSales.ProductsCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.ProductsPlusCatalogResponseDocument;
import org.openbravo.erpCommon.ws.externalSales.UploadOrdersResponseDocument;

public interface ExternalSalesService {

	ProductsPlusCatalogResponseDocument getProductsPlusCatalog(
			int entityId,
			int organizationId,
			int salesChannel,
			String username,
			String password)
			throws ServiceFault;

	UploadOrdersResponseDocument uploadOrders(
			int entityId,
			int organizationId,
			int salesChannel,
			ArrayOfTns1Order newOrders,
			String username,
			String password)
			throws ServiceFault;

	ProductsCatalogResponseDocument getProductsCatalog(
			int entityId,
			int organizationId,
			int salesChannel,
			String username,
			String password)
			throws ServiceFault;
}
