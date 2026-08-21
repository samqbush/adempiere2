package org.adempiere.phase3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;

import org.compiere.process.ProcessCall;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(Phase3MetadataTags.METADATA)
@Tag(Phase3MetadataTags.UNIT)
class MetadataExtensionGraphValidatorTest {

	@Test
	void reportsMissingAndWrongTypeBindingsWithRecordDetail() {
		ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve)
				throws ClassNotFoundException {

				if ("missing.Process".equals(name)) {
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		};
		MetadataExtensionGraphValidator validator =
			new MetadataExtensionGraphValidator(unusedConnection(), loader);

		assertNull(validator.validateBinding(
			"process-class", "AD_Process", 42, "Broken", "EXT",
			"missing.Process", ProcessCall.class));
		assertNull(validator.validateBinding(
			"process-class", "AD_Process", 43, "Wrong", "EXT",
			String.class.getName(), ProcessCall.class));
	}

	@Test
	void reportFailsClosedForZeroRecordsAndFormatsFindings() {
		assertThrows(AssertionError.class,
			() -> new MetadataValidationReport(0, List.of()).assertValid());

		MetadataFinding finding = new MetadataFinding(
			"process-class", "AD_Process", 42, "Broken", "EXT", "missing.Process",
			"class is absent");
		AssertionError error = assertThrows(AssertionError.class,
			() -> new MetadataValidationReport(1, List.of(finding)).assertValid());

		assertTrue(error.getMessage().contains("AD_Process[ID=42"));
		assertTrue(error.getMessage().contains("entityType=EXT"));
		assertTrue(error.getMessage().contains("class=missing.Process"));
		assertEquals(1, new MetadataValidationReport(1, List.of(finding)).findings().size());
	}

	private static Connection unusedConnection() {
		return (Connection) Proxy.newProxyInstance(
			MetadataExtensionGraphValidatorTest.class.getClassLoader(),
			new Class<?>[] { Connection.class },
			(proxy, method, arguments) -> {
				throw new AssertionError("Connection must not be used by binding tests");
			});
	}
}
