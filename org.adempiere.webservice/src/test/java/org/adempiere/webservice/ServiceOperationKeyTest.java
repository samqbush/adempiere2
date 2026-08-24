package org.adempiere.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ServiceOperationKeyTest {

	@Test
	void isAStableServiceAndOperationIdentity() {
		ServiceOperationKey first =
				new ServiceOperationKey("ADService", "getVersion");
		ServiceOperationKey second =
				new ServiceOperationKey("ADService", "getVersion");

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals("ADService.getVersion", first.toString());
	}

	@Test
	void rejectsBlankIdentityParts() {
		assertThrows(
				IllegalArgumentException.class,
				() -> new ServiceOperationKey(" ", "getVersion"));
		assertThrows(
				IllegalArgumentException.class,
				() -> new ServiceOperationKey("ADService", ""));
	}
}
