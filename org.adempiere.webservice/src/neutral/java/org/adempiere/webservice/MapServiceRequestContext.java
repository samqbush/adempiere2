package org.adempiere.webservice;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Thread-safe request context for transports whose service object already has
 * session lifetime, including the temporary session-scoped legacy adapter.
 */
public final class MapServiceRequestContext implements ServiceRequestContext {

	private final ConcurrentMap<String, Object> sessionValues =
			new ConcurrentHashMap<String, Object>();

	@Override
	public Object getSessionValue(String key) {
		return sessionValues.get(requireKey(key));
	}

	@Override
	public void setSessionValue(String key, Object value) {
		sessionValues.put(requireKey(key), Objects.requireNonNull(value, "value"));
	}

	@Override
	public void removeSessionValue(String key) {
		sessionValues.remove(requireKey(key));
	}

	@Override
	public Object getOrCreateSessionValue(String key, Supplier<?> factory) {
		Objects.requireNonNull(factory, "factory");
		return sessionValues.computeIfAbsent(
				requireKey(key),
				unused -> Objects.requireNonNull(factory.get(), "factory result"));
	}

	private static String requireKey(String key) {
		String value = Objects.requireNonNull(key, "key").trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("key must not be blank");
		}
		return value;
	}
}
