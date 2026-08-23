package org.adempiere.webservice.business;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.adempiere.webservice.ServiceFault;
import org.adempiere.webservice.ServiceOperationKey;
import org.adempiere.webservice.SoapOperationHandler;
import org.adempiere.webservice.SoapOperationRegistry;
import org.adempiere.webservice.SoapServiceDispatcher;
import org.openbravo.erpCommon.ws.externalSales.ArrayOfTns1Order;

import pl.x3E.adInterface.ADLoginRequestDocument;
import pl.x3E.adInterface.GetLookupSearchDataReqDocument;
import pl.x3E.adInterface.GetProcessParamsDocument;
import pl.x3E.adInterface.LocationDocument;
import pl.x3E.adInterface.ModelCRUDRequestDocument;
import pl.x3E.adInterface.ModelGetListRequestDocument;
import pl.x3E.adInterface.ModelRunProcessRequestDocument;
import pl.x3E.adInterface.ModelSetDocActionRequestDocument;
import pl.x3E.adInterface.RunProcessDocument;
import pl.x3E.adInterface.WindowTabDataDocument;
import pl.x3E.adInterface.WindowTabDataReqDocument;

/**
 * Binds the exact 33-operation neutral registry to transport-neutral business
 * services. Every handler validates its argument count and types explicitly.
 */
public final class BusinessSoapDispatcher {

	private BusinessSoapDispatcher() {
	}

	public static SoapServiceDispatcher create(ADServiceBusiness service) {
		Objects.requireNonNull(service, "service");
		return create(new DefaultBusinessServiceProvider(
				() -> service,
				DefaultModelADService::new,
				DefaultExternalSalesService::new,
				DefaultCustomerService::new));
	}

	public static SoapServiceDispatcher create(ModelADServiceBusiness service) {
		Objects.requireNonNull(service, "service");
		return create(new DefaultBusinessServiceProvider(
				DefaultADService::new,
				() -> service,
				DefaultExternalSalesService::new,
				DefaultCustomerService::new));
	}

	public static SoapServiceDispatcher create(ExternalSalesService service) {
		Objects.requireNonNull(service, "service");
		return create(new DefaultBusinessServiceProvider(
				DefaultADService::new,
				DefaultModelADService::new,
				() -> service,
				DefaultCustomerService::new));
	}

	public static SoapServiceDispatcher create(CustomerService service) {
		Objects.requireNonNull(service, "service");
		return create(new DefaultBusinessServiceProvider(
				DefaultADService::new,
				DefaultModelADService::new,
				DefaultExternalSalesService::new,
				() -> service));
	}

	public static SoapServiceDispatcher create(
			BusinessServiceProvider services) {
		Objects.requireNonNull(services, "services");
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				new LinkedHashMap<ServiceOperationKey, SoapOperationHandler>();

		put(handlers, "ADService", "getVersion", (args, context) -> {
			requireCount(args, 0, "ADService.getVersion");
			return services.getADService(context).getVersion();
		});
		put(handlers, "ADService", "getLookupSearchData", (args, context) ->
				services.getADService(context).getLookupSearchData(
						arg(args, 0, GetLookupSearchDataReqDocument.class,
								"ADService.getLookupSearchData")));
		put(handlers, "ADService", "getLocation", (args, context) ->
				services.getADService(context).getLocation(
						arg(args, 0, LocationDocument.class,
								"ADService.getLocation")));
		put(handlers, "ADService", "runProcess", (args, context) ->
				services.getADService(context).runProcess(
						arg(args, 0, RunProcessDocument.class,
								"ADService.runProcess")));
		put(handlers, "ADService", "getADMenu", (args, context) ->
				services.getADService(context).getADMenu(
						intArg(args, 0, "ADService.getADMenu")));
		put(handlers, "ADService", "getDataRow", (args, context) ->
				services.getADService(context).getDataRow(
						intArg(args, 0, "ADService.getDataRow"),
						intArg(args, 1, "ADService.getDataRow"),
						intArg(args, 2, "ADService.getDataRow")));
		put(handlers, "ADService", "saveLocation", (args, context) ->
				services.getADService(context).saveLocation(
						arg(args, 0, LocationDocument.class,
								"ADService.saveLocation")));
		put(handlers, "ADService", "login", (args, context) ->
				services.getADService(context).login(
						arg(args, 0, ADLoginRequestDocument.class,
								"ADService.login")));
		put(handlers, "ADService", "getADWindow", (args, context) ->
				services.getADService(context).getADWindow(
						intArg(args, 0, "ADService.getADWindow"),
						intArg(args, 1, "ADService.getADWindow"),
						intArg(args, 2, "ADService.getADWindow")));
		put(handlers, "ADService", "getLookupData", (args, context) ->
				services.getADService(context).getLookupData(
						intArg(args, 0, "ADService.getLookupData"),
						intArg(args, 1, "ADService.getLookupData"),
						intArg(args, 2, "ADService.getLookupData"),
						stringArg(args, 3, "ADService.getLookupData")));
		put(handlers, "ADService", "getDocAction", (args, context) ->
				services.getADService(context).getDocAction(
						intArg(args, 0, "ADService.getDocAction"),
						intArg(args, 1, "ADService.getDocAction"),
						intArg(args, 2, "ADService.getDocAction"),
						stringArg(args, 3, "ADService.getDocAction")));
		put(handlers, "ADService", "setDocAction", (args, context) ->
				services.getADService(context).setDocAction(
						intArg(args, 0, "ADService.setDocAction"),
						intArg(args, 1, "ADService.setDocAction"),
						intArg(args, 2, "ADService.setDocAction"),
						stringArg(args, 3, "ADService.setDocAction"),
						stringArg(args, 4, "ADService.setDocAction")));
		put(handlers, "ADService", "getWindowTabData", (args, context) ->
				services.getADService(context).getWindowTabData(
						arg(args, 0, WindowTabDataReqDocument.class,
								"ADService.getWindowTabData")));
		put(handlers, "ADService", "refreshDataRow", (args, context) ->
				services.getADService(context).refreshDataRow(
						intArg(args, 0, "ADService.refreshDataRow"),
						intArg(args, 1, "ADService.refreshDataRow"),
						intArg(args, 2, "ADService.refreshDataRow")));
		put(handlers, "ADService", "deleteDataRow", (args, context) ->
				services.getADService(context).deleteDataRow(
						intArg(args, 0, "ADService.deleteDataRow"),
						intArg(args, 1, "ADService.deleteDataRow"),
						intArg(args, 2, "ADService.deleteDataRow")));
		put(handlers, "ADService", "addNewDataRow", (args, context) ->
				services.getADService(context).addNewDataRow(
						intArg(args, 0, "ADService.addNewDataRow"),
						intArg(args, 1, "ADService.addNewDataRow")));
		put(handlers, "ADService", "isLoggedIn", (args, context) -> {
			requireCount(args, 0, "ADService.isLoggedIn");
			return Boolean.valueOf(services.getADService(context).isLoggedIn());
		});
		put(handlers, "ADService", "ignoreDataRow", (args, context) ->
				services.getADService(context).ignoreDataRow(
						intArg(args, 0, "ADService.ignoreDataRow"),
						intArg(args, 1, "ADService.ignoreDataRow"),
						intArg(args, 2, "ADService.ignoreDataRow")));
		put(handlers, "ADService", "saveDataRow", (args, context) ->
				services.getADService(context).saveDataRow(
						intArg(args, 0, "ADService.saveDataRow"),
						intArg(args, 1, "ADService.saveDataRow"),
						intArg(args, 2, "ADService.saveDataRow"),
						arg(args, 3, WindowTabDataDocument.class,
								"ADService.saveDataRow")));
		put(handlers, "ADService", "updateDataRow", (args, context) ->
				services.getADService(context).updateDataRow(
						intArg(args, 0, "ADService.updateDataRow"),
						intArg(args, 1, "ADService.updateDataRow"),
						intArg(args, 2, "ADService.updateDataRow"),
						arg(args, 3, WindowTabDataDocument.class,
								"ADService.updateDataRow")));
		put(handlers, "ADService", "getProcessParams", (args, context) ->
				services.getADService(context).getProcessParams(
						arg(args, 0, GetProcessParamsDocument.class,
								"ADService.getProcessParams")));

		put(handlers, "ModelADService", "setDocAction", (args, context) ->
				services.getModelADService().setDocAction(
						arg(args, 0, ModelSetDocActionRequestDocument.class,
								"ModelADService.setDocAction")));
		put(handlers, "ModelADService", "createData", (args, context) ->
				services.getModelADService().createData(
						arg(args, 0, ModelCRUDRequestDocument.class,
								"ModelADService.createData")));
		put(handlers, "ModelADService", "deleteData", (args, context) ->
				services.getModelADService().deleteData(
						arg(args, 0, ModelCRUDRequestDocument.class,
								"ModelADService.deleteData")));
		put(handlers, "ModelADService", "readData", (args, context) ->
				services.getModelADService().readData(
						arg(args, 0, ModelCRUDRequestDocument.class,
								"ModelADService.readData")));
		put(handlers, "ModelADService", "getList", (args, context) ->
				services.getModelADService().getList(
						arg(args, 0, ModelGetListRequestDocument.class,
								"ModelADService.getList")));
		put(handlers, "ModelADService", "runProcess", (args, context) ->
				services.getModelADService().runProcess(
						arg(args, 0, ModelRunProcessRequestDocument.class,
								"ModelADService.runProcess")));
		put(handlers, "ModelADService", "updateData", (args, context) ->
				services.getModelADService().updateData(
						arg(args, 0, ModelCRUDRequestDocument.class,
								"ModelADService.updateData")));
		put(handlers, "ModelADService", "queryData", (args, context) ->
				services.getModelADService().queryData(
						arg(args, 0, ModelCRUDRequestDocument.class,
								"ModelADService.queryData")));

		put(handlers, "ExternalSales", "getProductsPlusCatalog",
				(args, context) ->
						services.getExternalSalesService().getProductsPlusCatalog(
								intArg(args, 0,
										"ExternalSales.getProductsPlusCatalog"),
								intArg(args, 1,
										"ExternalSales.getProductsPlusCatalog"),
								intArg(args, 2,
										"ExternalSales.getProductsPlusCatalog"),
								stringArg(args, 3,
										"ExternalSales.getProductsPlusCatalog"),
								stringArg(args, 4,
										"ExternalSales.getProductsPlusCatalog")));
		put(handlers, "ExternalSales", "getProductsCatalog", (args, context) ->
				services.getExternalSalesService().getProductsCatalog(
						intArg(args, 0, "ExternalSales.getProductsCatalog"),
						intArg(args, 1, "ExternalSales.getProductsCatalog"),
						intArg(args, 2, "ExternalSales.getProductsCatalog"),
						stringArg(args, 3, "ExternalSales.getProductsCatalog"),
						stringArg(args, 4, "ExternalSales.getProductsCatalog")));
		put(handlers, "ExternalSales", "uploadOrders", (args, context) ->
				services.getExternalSalesService().uploadOrders(
						intArg(args, 0, "ExternalSales.uploadOrders"),
						intArg(args, 1, "ExternalSales.uploadOrders"),
						intArg(args, 2, "ExternalSales.uploadOrders"),
						arg(args, 3, ArrayOfTns1Order.class,
								"ExternalSales.uploadOrders"),
						stringArg(args, 4, "ExternalSales.uploadOrders"),
						stringArg(args, 5, "ExternalSales.uploadOrders")));

		put(handlers, "WebService", "getCustomers", (args, context) ->
				services.getCustomerService().getCustomers(
						intArg(args, 0, "WebService.getCustomers"),
						stringArg(args, 1, "WebService.getCustomers"),
						stringArg(args, 2, "WebService.getCustomers")));

		return new SoapOperationRegistry(handlers);
	}

	private static void put(
			Map<ServiceOperationKey, SoapOperationHandler> handlers,
			String service,
			String operation,
			SoapOperationHandler handler) {
		ServiceOperationKey key = new ServiceOperationKey(service, operation);
		int expectedArguments = expectedArgumentCount(key);
		SoapOperationHandler validatingHandler = (arguments, context) -> {
			requireCount(arguments, expectedArguments, key.toString());
			return handler.invoke(arguments, context);
		};
		if (handlers.put(key, validatingHandler) != null) {
			throw new IllegalStateException("Duplicate SOAP handler " + key);
		}
	}

	private static int expectedArgumentCount(ServiceOperationKey key) {
		String operation = key.toString();
		switch (operation) {
			case "ADService.getVersion":
			case "ADService.isLoggedIn":
				return 0;
			case "ADService.getLookupSearchData":
			case "ADService.getLocation":
			case "ADService.runProcess":
			case "ADService.getADMenu":
			case "ADService.saveLocation":
			case "ADService.login":
			case "ADService.getWindowTabData":
			case "ADService.getProcessParams":
			case "ModelADService.setDocAction":
			case "ModelADService.createData":
			case "ModelADService.deleteData":
			case "ModelADService.readData":
			case "ModelADService.getList":
			case "ModelADService.runProcess":
			case "ModelADService.updateData":
			case "ModelADService.queryData":
				return 1;
			case "ADService.addNewDataRow":
				return 2;
			case "ADService.getDataRow":
			case "ADService.getADWindow":
			case "ADService.refreshDataRow":
			case "ADService.deleteDataRow":
			case "ADService.ignoreDataRow":
			case "WebService.getCustomers":
				return 3;
			case "ADService.getLookupData":
			case "ADService.getDocAction":
			case "ADService.saveDataRow":
			case "ADService.updateDataRow":
				return 4;
			case "ADService.setDocAction":
			case "ExternalSales.getProductsPlusCatalog":
			case "ExternalSales.getProductsCatalog":
				return 5;
			case "ExternalSales.uploadOrders":
				return 6;
			default:
				throw new IllegalStateException(
						"No argument contract for " + operation);
		}
	}

	private static int intArg(
			List<?> arguments, int index, String operation) throws ServiceFault {
		Integer value = arg(arguments, index, Integer.class, operation);
		if (value == null) {
			throw invalidArguments(
					operation, "argument " + index + " must not be null");
		}
		return value.intValue();
	}

	private static String stringArg(
			List<?> arguments, int index, String operation) throws ServiceFault {
		return arg(arguments, index, String.class, operation);
	}

	private static <T> T arg(
			List<?> arguments,
			int index,
			Class<T> type,
			String operation) throws ServiceFault {
		requireCountAtLeast(arguments, index + 1, operation);
		Object value = arguments.get(index);
		if (value == null) {
			return null;
		}
		if (!type.isInstance(value)) {
			throw invalidArguments(
					operation,
					"argument " + index + " must be " + type.getName());
		}
		return type.cast(value);
	}

	private static void requireCount(
			List<?> arguments, int expected, String operation)
			throws ServiceFault {
		if (arguments.size() != expected) {
			throw invalidArguments(
					operation,
					"expected " + expected + " arguments, got "
							+ arguments.size());
		}
	}

	private static void requireCountAtLeast(
			List<?> arguments, int expected, String operation)
			throws ServiceFault {
		if (arguments.size() < expected) {
			throw invalidArguments(
					operation,
					"expected at least " + expected + " arguments, got "
							+ arguments.size());
		}
	}

	private static ServiceFault invalidArguments(
			String operation, String detail) {
		return new ServiceFault(
				"INVALID_ARGUMENTS",
				"Invalid SOAP arguments for " + operation + ": " + detail,
				operation);
	}
}
