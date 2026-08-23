package org.adempiere.webservice;

import java.util.Objects;

import javax.xml.namespace.QName;

public final class ServiceFault extends Exception {

	private static final long serialVersionUID = 1L;

	private final String code;
	private final QName faultCode;
	private final String detail;

	public ServiceFault(String code, String message, String detail) {
		this(code, message, new QName(requireText(code, "code")), detail, null);
	}

	public ServiceFault(
			String code,
			String message,
			String detail,
			Throwable cause) {
		this(code, message, new QName(requireText(code, "code")), detail, cause);
	}

	public ServiceFault(
			String code,
			String message,
			QName faultCode,
			String detail,
			Throwable cause) {
		super(requireMessage(message), cause);
		this.code = requireText(code, "code");
		this.faultCode = Objects.requireNonNull(faultCode, "faultCode");
		this.detail = detail;
	}

	public String getCode() {
		return code;
	}

	public String getDetail() {
		return detail;
	}

	public QName getFaultCode() {
		return faultCode;
	}

	private static String requireText(String value, String label) {
		String text = Objects.requireNonNull(value, label).trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return text;
	}

	private static String requireMessage(String message) {
		String value = Objects.requireNonNull(message, "message");
		if (value.trim().isEmpty()) {
			throw new IllegalArgumentException("message must not be blank");
		}
		return value;
	}
}
