package org.adempiere.web.context;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Arms one independently deployed Phase 5f context adapter. */
public final class ContextRoutingStartupListener implements ServletContextListener {

	static final String FILTER_NAME = "phase5fContextRouter";

	@Override
	public void contextInitialized(ServletContextEvent event) {
		String contextPath = event.getServletContext().getContextPath();
		if (org.adempiere.web.route.ContextRoutingPolicy.forContext(contextPath)
				== null) {
			throw new IllegalStateException(
					"No reviewed Phase 5f policy exists for " + contextPath);
		}
		if (event.getServletContext().getFilterRegistration(FILTER_NAME) == null) {
			throw new IllegalStateException(
					"WEB-INF/web.xml does not register " + FILTER_NAME);
		}
		ContextRoutingBridge.initialise();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		ContextRoutingBridge.shutdown();
	}
}
