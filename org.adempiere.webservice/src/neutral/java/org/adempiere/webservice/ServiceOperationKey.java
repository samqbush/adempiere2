package org.adempiere.webservice;

import java.util.Objects;

public final class ServiceOperationKey {

	private final String service;
	private final String operation;

	public ServiceOperationKey(String service, String operation) {
		this.service = requireName(service, "service");
		this.operation = requireName(operation, "operation");
	}

	public String getService() {
		return service;
	}

	public String getOperation() {
		return operation;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ServiceOperationKey)) {
			return false;
		}
		ServiceOperationKey key = (ServiceOperationKey) other;
		return service.equals(key.service) && operation.equals(key.operation);
	}

	@Override
	public int hashCode() {
		return Objects.hash(service, operation);
	}

	@Override
	public String toString() {
		return service + "." + operation;
	}

	private static String requireName(String value, String label) {
		String name = Objects.requireNonNull(value, label).trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return name;
	}
}
