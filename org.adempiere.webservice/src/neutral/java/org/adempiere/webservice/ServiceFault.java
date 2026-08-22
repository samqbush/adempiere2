package org.adempiere.webservice;

import java.util.Objects;

public final class ServiceFault extends Exception {

	private static final long serialVersionUID = 1L;

	private final String code;
	private final String detail;

	public ServiceFault(String code, String message, String detail) {
		super(Objects.requireNonNull(message, "message"));
		this.code = Objects.requireNonNull(code, "code");
		this.detail = detail;
	}

	public String getCode() {
		return code;
	}

	public String getDetail() {
		return detail;
	}
}
