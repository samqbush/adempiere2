package org.adempiere.webservice.business;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;

import javax.xml.namespace.QName;

import org.adempiere.webservice.ServiceFault;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import pl.x3E.adInterface.DataField;
import pl.x3E.adInterface.DataRow;

/**
 * Isolated coverage for the extracted model-service value conversion.
 * The conversion is the only part of the extraction whose dependencies can be
 * injected without an application dictionary or a database connection.
 */
@Tag("UnitTest")
class DefaultModelADServiceTest {

	private static final String WEB_SERVICE_TYPE_VALUE = "CreateBPartner";

	@Test
	void treatsMissingAndEmptyValuesAsNull() throws Exception {
		assertNull(DefaultModelADService.toColumnValue(
				String.class, field("Name", null), WEB_SERVICE_TYPE_VALUE));
		assertNull(DefaultModelADService.toColumnValue(
				Integer.class, field("C_BPartner_ID", ""), WEB_SERVICE_TYPE_VALUE));
	}

	@Test
	void mapsTheLegacyBooleanVocabulary() throws Exception {
		assertSame(Boolean.TRUE, DefaultModelADService.toColumnValue(
				Boolean.class, field("IsActive", "Y"), WEB_SERVICE_TYPE_VALUE));
		assertSame(Boolean.TRUE, DefaultModelADService.toColumnValue(
				Boolean.class, field("IsActive", "true"), WEB_SERVICE_TYPE_VALUE));
		assertSame(Boolean.FALSE, DefaultModelADService.toColumnValue(
				Boolean.class, field("IsActive", "N"), WEB_SERVICE_TYPE_VALUE));
		assertSame(Boolean.FALSE, DefaultModelADService.toColumnValue(
				Boolean.class, field("IsActive", "FALSE"), WEB_SERVICE_TYPE_VALUE));
	}

	@Test
	void rejectsAnUnknownBooleanWithTheLegacyMessageAndFaultCode() {
		ServiceFault fault = assertThrows(
				ServiceFault.class,
				() -> DefaultModelADService.toColumnValue(
						Boolean.class,
						field("IsActive", "maybe"),
						WEB_SERVICE_TYPE_VALUE));

		assertEquals(
				"Web service type CreateBPartner: input column IsActive"
						+ " wrong value maybe",
				fault.getMessage());
		assertEquals(
				new QName("setValueAccordingToClass"), fault.getFaultCode());
		assertNull(fault.getCause());
		assertNull(fault.getDetail());
	}

	@Test
	void convertsNumericTemporalAndBinaryColumns() throws Exception {
		assertEquals(Integer.valueOf(118), DefaultModelADService.toColumnValue(
				Integer.class, field("C_BPartner_ID", "118"), WEB_SERVICE_TYPE_VALUE));
		assertEquals(new BigDecimal("12.34"), DefaultModelADService.toColumnValue(
				BigDecimal.class, field("PriceList", "12.34"), WEB_SERVICE_TYPE_VALUE));
		assertEquals(
				Timestamp.valueOf("2020-01-02 03:04:05"),
				DefaultModelADService.toColumnValue(
						Timestamp.class,
						field("DateOrdered", "2020-01-02 03:04:05"),
						WEB_SERVICE_TYPE_VALUE));

		byte[] binary = "adempiere".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(binary, (byte[]) DefaultModelADService.toColumnValue(
				byte[].class,
				field("BinaryData", Base64.getEncoder().encodeToString(binary)),
				WEB_SERVICE_TYPE_VALUE));
	}

	@Test
	void passesUnrecognizedColumnClassesThroughAsText() throws Exception {
		assertEquals("Joe Block", DefaultModelADService.toColumnValue(
				String.class, field("Name", "Joe Block"), WEB_SERVICE_TYPE_VALUE));
	}

	@Test
	void reportsTheLegacyConversionFailureTextForEveryTypedColumn() {
		assertConversionFault(
				Integer.class, "C_BPartner_ID", "not-a-number", () -> {
					Integer.parseInt("not-a-number");
				});
		assertConversionFault(
				BigDecimal.class, "PriceList", "not-a-decimal", () -> {
					new BigDecimal("not-a-decimal");
				});
		assertConversionFault(
				Timestamp.class, "DateOrdered", "not-a-timestamp", () -> {
					Timestamp.valueOf("not-a-timestamp");
				});
	}

	private static void assertConversionFault(
			Class<?> columnClass,
			String columnName,
			String value,
			Runnable legacyConversion) {
		Exception legacyFailure = assertThrows(
				Exception.class, () -> legacyConversion.run());

		ServiceFault fault = assertThrows(
				ServiceFault.class,
				() -> DefaultModelADService.toColumnValue(
						columnClass,
						field(columnName, value),
						WEB_SERVICE_TYPE_VALUE));

		assertEquals(
				legacyFailure.getClass().toString() + " "
						+ legacyFailure.getMessage() + " for " + columnName,
				fault.getMessage());
		assertEquals(
				new QName("setValueAccordingToClass"), fault.getFaultCode());
		assertSame(legacyFailure.getCause(), fault.getCause());
	}

	private static DataField field(String column, String value) {
		DataField field = DataRow.Factory.newInstance().addNewField();
		field.setColumn(column);
		if (value != null) {
			field.setVal(value);
		}
		return field;
	}
}
