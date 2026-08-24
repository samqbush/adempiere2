package org.adempiere.webservice;

import static org.adempiere.webservice.ServiceScope.REQUEST;
import static org.adempiere.webservice.ServiceScope.SESSION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SoapOperationRegistry implements SoapServiceDispatcher {

	private static final List<SoapOperationDefinition> REQUIRED_OPERATIONS =
			createRequiredOperations();

	private final Map<ServiceOperationKey, SoapOperationHandler> handlers;

	public SoapOperationRegistry(
			Map<ServiceOperationKey, SoapOperationHandler> handlers) {
		Objects.requireNonNull(handlers, "handlers");
		Set<ServiceOperationKey> required = requiredOperationKeys();
		Set<ServiceOperationKey> provided = new LinkedHashSet<>(handlers.keySet());
		if (!required.equals(provided)) {
			Set<ServiceOperationKey> missing = new LinkedHashSet<>(required);
			missing.removeAll(provided);
			Set<ServiceOperationKey> unexpected = new LinkedHashSet<>(provided);
			unexpected.removeAll(required);
			throw new IllegalArgumentException(
					"SOAP operation handlers differ; missing=" + missing
							+ ", unexpected=" + unexpected);
		}
		if (handlers.values().stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException(
					"SOAP operation handlers must not contain null values");
		}
		this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
	}

	@Override
	public Object invoke(
			String service,
			String operation,
			List<?> arguments,
			ServiceRequestContext context)
			throws ServiceFault {
		ServiceOperationKey key = new ServiceOperationKey(service, operation);
		SoapOperationHandler handler = handlers.get(key);
		if (handler == null) {
			throw new ServiceFault(
					"UNKNOWN_OPERATION",
					"Unknown SOAP service operation " + key,
					key.toString());
		}
		return handler.invoke(
				Objects.requireNonNull(arguments, "arguments"),
				Objects.requireNonNull(context, "context"));
	}

	public static List<SoapOperationDefinition> requiredOperations() {
		return REQUIRED_OPERATIONS;
	}

	public static Set<ServiceOperationKey> requiredOperationKeys() {
		Set<ServiceOperationKey> keys = new LinkedHashSet<>();
		for (SoapOperationDefinition definition : REQUIRED_OPERATIONS) {
			keys.add(definition.getKey());
		}
		return Collections.unmodifiableSet(keys);
	}

	private static List<SoapOperationDefinition> createRequiredOperations() {
		List<SoapOperationDefinition> operations = new ArrayList<>();
		add(operations, "ADService", SESSION,
				"getVersion", "getLookupSearchData", "getLocation", "runProcess",
				"getADMenu", "getDataRow", "saveLocation", "login",
				"getADWindow", "getLookupData", "getDocAction", "setDocAction",
				"getWindowTabData", "refreshDataRow", "deleteDataRow",
				"addNewDataRow", "isLoggedIn", "ignoreDataRow", "saveDataRow",
				"updateDataRow", "getProcessParams");
		add(operations, "ModelADService", REQUEST,
				"setDocAction", "createData", "deleteData", "readData",
				"getList", "runProcess", "updateData", "queryData");
		add(operations, "ExternalSales", REQUEST,
				"getProductsPlusCatalog", "getProductsCatalog", "uploadOrders");
		add(operations, "WebService", REQUEST, "getCustomers");
		if (operations.size() != 33) {
			throw new IllegalStateException(
					"Expected 33 required SOAP operations, got " + operations.size());
		}
		return Collections.unmodifiableList(operations);
	}

	private static void add(
			List<SoapOperationDefinition> definitions,
			String service,
			ServiceScope scope,
			String... operations) {
		for (String operation : operations) {
			definitions.add(new SoapOperationDefinition(service, operation, scope));
		}
	}
}
