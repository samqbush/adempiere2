package org.adempiere.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class SoapOperationRegistryTest {

	@Test
	void requiredRegistryMatchesTheFourServiceContract() {
		assertEquals(33, SoapOperationRegistry.requiredOperations().size());
		assertEquals(21, countScope(ServiceScope.SESSION));
		assertEquals(12, countScope(ServiceScope.REQUEST));
		assertEquals(4, SoapOperationRegistry.requiredOperations().stream()
				.map(definition -> definition.getKey().getService())
				.distinct()
				.count());
	}

	@Test
	void requiredRegistryExactlyMatchesTheFrozenOperationManifest()
			throws IOException {
		Set<ServiceOperationKey> manifestKeys = new LinkedHashSet<>();
		Map<ServiceOperationKey, ServiceScope> manifestScopes =
				new LinkedHashMap<>();
		InputStream manifest = getClass().getResourceAsStream("/operations.tsv");
		if (manifest == null) {
			throw new IllegalStateException("operations.tsv test resource is missing");
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				manifest, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("#") || line.trim().isEmpty()) {
					continue;
				}
				String[] fields = line.split("\t", -1);
				ServiceOperationKey key =
						new ServiceOperationKey(fields[0], fields[1]);
				manifestKeys.add(key);
				manifestScopes.put(
						key,
						ServiceScope.valueOf(fields[13].toUpperCase()));
			}
		}

		assertEquals(manifestKeys, SoapOperationRegistry.requiredOperationKeys());
		for (SoapOperationDefinition definition
				: SoapOperationRegistry.requiredOperations()) {
			assertEquals(
					manifestScopes.get(definition.getKey()),
					definition.getScope(),
					definition.getKey().toString());
		}
	}

	@Test
	void dispatchesOnlyAnExplicitlyRegisteredOperation() throws Exception {
		Object expected = new Object();
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				completeHandlers(expected);
		SoapOperationRegistry registry = new SoapOperationRegistry(handlers);

		Object actual = registry.invoke(
				"ADService",
				"getVersion",
				Collections.emptyList(),
				new MapRequestContext());

		assertSame(expected, actual);
	}

	@Test
	void rejectsAnUnknownOperationWithoutFallback() {
		SoapOperationRegistry registry = new SoapOperationRegistry(
				completeHandlers(new Object()));

		ServiceFault fault = assertThrows(ServiceFault.class, () ->
				registry.invoke(
						"ADService",
						"notPublished",
						Collections.emptyList(),
						new MapRequestContext()));

		assertEquals("UNKNOWN_OPERATION", fault.getCode());
		assertEquals("ADService.notPublished", fault.getDetail());
	}

	@Test
	void rejectsAnIncompleteHandlerSet() {
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				completeHandlers(new Object());
		handlers.remove(new ServiceOperationKey("WebService", "getCustomers"));

		assertThrows(
				IllegalArgumentException.class,
				() -> new SoapOperationRegistry(handlers));
	}

	@Test
	void rejectsAnUnexpectedHandler() {
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				completeHandlers(new Object());
		handlers.put(
				new ServiceOperationKey("ADService", "notPublished"),
			(arguments, context) -> new Object());

		assertThrows(
				IllegalArgumentException.class,
				() -> new SoapOperationRegistry(handlers));
	}

	@Test
	void preservesNeutralFaultMetadataFromTheHandler() {
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				completeHandlers(new Object());
		ServiceFault expected =
				new ServiceFault("BUSINESS_FAULT", "Rejected", "<detail/>");
		handlers.put(
				new ServiceOperationKey("ADService", "getVersion"),
				(request, context) -> {
					throw expected;
				});
		SoapOperationRegistry registry = new SoapOperationRegistry(handlers);

		ServiceFault actual = assertThrows(ServiceFault.class, () ->
				registry.invoke(
						"ADService",
						"getVersion",
						Collections.emptyList(),
						new MapRequestContext()));

		assertSame(expected, actual);
	}

	private static long countScope(ServiceScope scope) {
		return SoapOperationRegistry.requiredOperations().stream()
				.filter(definition -> definition.getScope() == scope)
				.count();
	}

	private static Map<ServiceOperationKey, SoapOperationHandler> completeHandlers(
			Object response) {
		Map<ServiceOperationKey, SoapOperationHandler> handlers =
				new LinkedHashMap<>();
		for (ServiceOperationKey key : SoapOperationRegistry.requiredOperationKeys()) {
			handlers.put(key, (request, context) -> response);
		}
		return handlers;
	}

	private static final class MapRequestContext
			implements ServiceRequestContext {

		private final Map<String, Object> values = new LinkedHashMap<>();

		@Override
		public Object getSessionValue(String key) {
			return values.get(key);
		}

		@Override
		public void setSessionValue(String key, Object value) {
			values.put(key, value);
		}

		@Override
		public void removeSessionValue(String key) {
			values.remove(key);
		}

		@Override
		public synchronized Object getOrCreateSessionValue(
				String key, java.util.function.Supplier<?> factory) {
			Object value = values.get(key);
			if (value == null) {
				value = factory.get();
				values.put(key, value);
			}
			return value;
		}
	}
}
