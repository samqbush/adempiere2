package org.adempiere.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class MapServiceRequestContextTest {

	@Test
	void storesRemovesAndCreatesSessionValues() {
		MapServiceRequestContext context = new MapServiceRequestContext();
		Object direct = new Object();
		Object created = new Object();

		context.setSessionValue("direct", direct);
		assertSame(direct, context.getSessionValue("direct"));
		assertSame(
				created,
				context.getOrCreateSessionValue("created", () -> created));
		assertSame(
				created,
				context.getOrCreateSessionValue("created", Object::new));
		context.removeSessionValue("direct");
		assertEquals(null, context.getSessionValue("direct"));
	}

	@Test
	void createsOneValueUnderConcurrentSessionAccess() throws Exception {
		MapServiceRequestContext context = new MapServiceRequestContext();
		AtomicInteger creations = new AtomicInteger();
		Callable<Object> lookup = () -> context.getOrCreateSessionValue(
				"session-service",
				() -> {
					creations.incrementAndGet();
					return new Object();
				});
		ExecutorService workers = Executors.newFixedThreadPool(8);
		try {
			List<Future<Object>> results = new ArrayList<Future<Object>>();
			for (int index = 0; index < 32; index++) {
				results.add(workers.submit(lookup));
			}
			Object expected = results.get(0).get(5, TimeUnit.SECONDS);
			for (Future<Object> result : results) {
				assertSame(expected, result.get(5, TimeUnit.SECONDS));
			}
		} finally {
			workers.shutdownNow();
			workers.awaitTermination(5, TimeUnit.SECONDS);
		}
		assertEquals(1, creations.get());
	}

	@Test
	void rejectsBlankKeysAndNullValues() {
		MapServiceRequestContext context = new MapServiceRequestContext();

		assertThrows(
				IllegalArgumentException.class,
				() -> context.getSessionValue(" "));
		assertThrows(
				NullPointerException.class,
				() -> context.setSessionValue("key", null));
		assertThrows(
				NullPointerException.class,
				() -> context.getOrCreateSessionValue("key", () -> null));
	}
}
