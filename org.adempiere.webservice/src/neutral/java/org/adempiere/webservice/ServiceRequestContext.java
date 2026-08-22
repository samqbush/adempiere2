package org.adempiere.webservice;

public interface ServiceRequestContext {

	Object getSessionValue(String key);

	void setSessionValue(String key, Object value);

	void removeSessionValue(String key);
}
