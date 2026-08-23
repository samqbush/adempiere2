package org.adempiere.webservice.cxf;

import java.util.Objects;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpSession;

import org.adempiere.webservice.ServiceRequestContext;

final class HttpSessionServiceRequestContext implements ServiceRequestContext {

	private final HttpSession session;

	HttpSessionServiceRequestContext(HttpSession session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	@Override
	public Object getSessionValue(String key) {
		return session.getAttribute(key);
	}

	@Override
	public void setSessionValue(String key, Object value) {
		session.setAttribute(key, value);
	}

	@Override
	public void removeSessionValue(String key) {
		session.removeAttribute(key);
	}

	@Override
	public Object getOrCreateSessionValue(String key, Supplier<?> factory) {
		synchronized (session) {
			Object value = session.getAttribute(key);
			if (value == null) {
				value = Objects.requireNonNull(factory.get(), "factory value");
				session.setAttribute(key, value);
			}
			return value;
		}
	}
}
