package org.adempiere.webservice.business;

import org.adempiere.webservice.ServiceFault;

import pl.x3E.adInterface.ADLoginRequestDocument;
import pl.x3E.adInterface.ADLoginResponseDocument;
import pl.x3E.adInterface.ADMenuDocument;
import pl.x3E.adInterface.DocActionDocument;
import pl.x3E.adInterface.GetLookupSearchDataReqDocument;
import pl.x3E.adInterface.GetProcessParamsDocument;
import pl.x3E.adInterface.LocationDocument;
import pl.x3E.adInterface.ProcessParamsDocument;
import pl.x3E.adInterface.RunProcessDocument;
import pl.x3E.adInterface.RunProcessResponseDocument;
import pl.x3E.adInterface.StandardResponseDocument;
import pl.x3E.adInterface.WindowDocument;
import pl.x3E.adInterface.WindowTabDataDocument;
import pl.x3E.adInterface.WindowTabDataReqDocument;

/**
 * Transport-neutral contract for the 21 session-scoped {@code ADService}
 * operations. One implementation instance owns one SOAP session's mutable
 * login, window, and row state.
 */
public interface ADServiceBusiness {

	WindowDocument getADWindow(
			int windowNo, int windowId, int menuId) throws ServiceFault;

	WindowTabDataDocument getWindowTabData(WindowTabDataReqDocument request)
			throws ServiceFault;

	WindowTabDataDocument getDataRow(int windowNo, int tabNo, int rowNo)
			throws ServiceFault;

	WindowTabDataDocument updateDataRow(
			int windowNo,
			int tabNo,
			int rowNo,
			WindowTabDataDocument data) throws ServiceFault;

	WindowTabDataDocument saveDataRow(
			int windowNo,
			int tabNo,
			int rowNo,
			WindowTabDataDocument data) throws ServiceFault;

	WindowTabDataDocument addNewDataRow(int windowNo, int tabNo)
			throws ServiceFault;

	WindowTabDataDocument deleteDataRow(int windowNo, int tabNo, int rowNo)
			throws ServiceFault;

	WindowTabDataDocument ignoreDataRow(int windowNo, int tabNo, int rowNo)
			throws ServiceFault;

	WindowTabDataDocument refreshDataRow(int windowNo, int tabNo, int rowNo)
			throws ServiceFault;

	WindowTabDataDocument getLookupSearchData(
			GetLookupSearchDataReqDocument request) throws ServiceFault;

	WindowTabDataDocument getLookupData(
			int windowNo, int tabNo, int rowNo, String columnName)
			throws ServiceFault;

	ADMenuDocument getADMenu(int roleId) throws ServiceFault;

	ADLoginResponseDocument login(ADLoginRequestDocument request)
			throws ServiceFault;

	ProcessParamsDocument getProcessParams(GetProcessParamsDocument request)
			throws ServiceFault;

	RunProcessResponseDocument runProcess(RunProcessDocument request)
			throws ServiceFault;

	StandardResponseDocument saveLocation(LocationDocument request)
			throws ServiceFault;

	LocationDocument getLocation(LocationDocument request) throws ServiceFault;

	DocActionDocument getDocAction(
			int windowNo, int tabNo, int rowNo, String columnName)
			throws ServiceFault;

	StandardResponseDocument setDocAction(
			int windowNo,
			int tabNo,
			int rowNo,
			String columnName,
			String docAction) throws ServiceFault;

	String getVersion();

	boolean isLoggedIn();
}
