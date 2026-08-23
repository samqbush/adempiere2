package org.adempiere.webservice;

import java.util.Objects;

public final class SoapOperationDefinition {

	private final ServiceOperationKey key;
	private final ServiceScope scope;

	public SoapOperationDefinition(
			String service,
			String operation,
			ServiceScope scope) {
		key = new ServiceOperationKey(service, operation);
		this.scope = Objects.requireNonNull(scope, "scope");
	}

	public ServiceOperationKey getKey() {
		return key;
	}

	public ServiceScope getScope() {
		return scope;
	}
}
