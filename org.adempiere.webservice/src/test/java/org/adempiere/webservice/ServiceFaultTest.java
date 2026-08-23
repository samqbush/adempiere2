package org.adempiere.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ServiceFaultTest {

	@Test
	void preservesFrameworkNeutralFaultMetadataAndCause() {
		IllegalStateException cause = new IllegalStateException("source");
		QName faultCode = new QName("username");
		ServiceFault fault = new ServiceFault(
				"AUTHENTICATION",
				"Denied",
				faultCode,
				"<detail/>",
				cause);

		assertEquals("AUTHENTICATION", fault.getCode());
		assertEquals(faultCode, fault.getFaultCode());
		assertEquals("Denied", fault.getMessage());
		assertEquals("<detail/>", fault.getDetail());
		assertSame(cause, fault.getCause());
	}

	@Test
	void rejectsBlankRequiredMetadata() {
		assertThrows(
				IllegalArgumentException.class,
				() -> new ServiceFault(" ", "Denied", null));
		assertThrows(
				IllegalArgumentException.class,
				() -> new ServiceFault("AUTHENTICATION", "", null));
	}

	@Test
	void preservesWireVisibleMessageWhitespace() {
		ServiceFault fault =
				new ServiceFault("VALIDATION", "wrong value maybe ", null);

		assertEquals("wrong value maybe ", fault.getMessage());
	}
}
