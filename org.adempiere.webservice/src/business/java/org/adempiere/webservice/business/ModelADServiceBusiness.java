package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceFault;

import pl.x3E.adInterface.ModelCRUDRequestDocument;
import pl.x3E.adInterface.ModelGetListRequestDocument;
import pl.x3E.adInterface.ModelRunProcessRequestDocument;
import pl.x3E.adInterface.ModelSetDocActionRequestDocument;
import pl.x3E.adInterface.RunProcessResponseDocument;
import pl.x3E.adInterface.StandardResponseDocument;
import pl.x3E.adInterface.WindowTabDataDocument;

/**
 * Transport-neutral contract for the eight request-scoped
 * {@code ModelADService} operations. Implementations return the same XMLBeans
 * documents the published SOAP contract binds and raise {@link ServiceFault}
 * instead of any transport framework fault type.
 */
public interface ModelADServiceBusiness {

	StandardResponseDocument setDocAction(ModelSetDocActionRequestDocument req)
			throws ServiceFault;

	RunProcessResponseDocument runProcess(ModelRunProcessRequestDocument req)
			throws ServiceFault;

	WindowTabDataDocument getList(ModelGetListRequestDocument req)
			throws ServiceFault;

	StandardResponseDocument createData(ModelCRUDRequestDocument req)
			throws ServiceFault;

	StandardResponseDocument updateData(ModelCRUDRequestDocument req)
			throws ServiceFault;

	StandardResponseDocument deleteData(ModelCRUDRequestDocument req)
			throws ServiceFault;

	WindowTabDataDocument readData(ModelCRUDRequestDocument req)
			throws ServiceFault;

	WindowTabDataDocument queryData(ModelCRUDRequestDocument req)
			throws ServiceFault;
}
