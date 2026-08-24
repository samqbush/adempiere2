package org.adempiere.webservice;

import java.util.function.Supplier;

public interface ServiceRequestContext {

	Object getSessionValue(String key);

	void setSessionValue(String key, Object value);

	void removeSessionValue(String key);

	Object getOrCreateSessionValue(String key, Supplier<?> factory);
}
