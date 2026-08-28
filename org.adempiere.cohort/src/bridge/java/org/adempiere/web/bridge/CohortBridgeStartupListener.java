package org.adempiere.web.bridge;

import java.util.logging.Level;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.compiere.util.CLogger;

/**
 * Initialises the Phase 5e bridge and asserts, at startup, that the derived WAR
 * carries a <em>complete and consistent</em> set of Phase 5e components.
 *
 * <p>This is the H1 mitigation for the installed product. The installer rebuilds
 * {@code webui.war} on every {@code setupWLib} run by merging
 * {@code zkcustomization.jar}, the 2Pack package jars and {@code zkpatches.jar}
 * over the base archive with first-seen-wins precedence
 * ({@code install/Adempiere/build.xml}). A site customisation can therefore
 * replace {@code WEB-INF/web.xml} or {@code WEB-INF/zk.xml} and silently
 * un-register the router or the interceptor, leaving an archive that starts
 * cleanly and routes nobody - or worse, one that routes without a decision.
 *
 * <p>So the startup assertion is deliberately loud and deliberately fatal:
 *
 * <ul>
 *   <li>every Phase 5e class the derived overlay ships must be loadable;</li>
 *   <li>the routing filter must be registered on {@code /*};</li>
 *   <li>the ZK event interceptor must be named in the deployed
 *       {@code WEB-INF/zk.xml};</li>
 *   <li>a partial set fails deployment rather than degrading quietly.</li>
 * </ul>
 */
public class CohortBridgeStartupListener implements ServletContextListener {

	/** The filter registration the derived descriptor must declare. */
	static final String FILTER_NAME = "phase5eCohortRouter";

	private static final CLogger log =
			CLogger.getCLogger(CohortBridgeStartupListener.class);

	@Override
	public void contextInitialized(ServletContextEvent event) {
		StringBuilder problems = new StringBuilder();

		for (String required : new String[] {
				CohortRoutingFilter.class.getName(),
				CohortDecisionInterceptor.class.getName(),
				"org.adempiere.web.cohort.CohortSelector",
				"org.adempiere.web.handoff.HandoffTicketCodec",
				"org.adempiere.web.route.PublicRouteClassifier",
				"org.adempiere.web.route.RoutingCore",
				"org.adempiere.web.route.RoutingLifecycle",
				"org.adempiere.web.route.LoopbackProxy"}) {
			try {
				Class.forName(required, false, getClass().getClassLoader());
			} catch (ClassNotFoundException | LinkageError missing) {
				problems.append("\n  - the Phase 5e class ").append(required)
						.append(" is missing from this deployment");
			}
		}

		if (event.getServletContext().getFilterRegistration(FILTER_NAME) == null) {
			problems.append("\n  - WEB-INF/web.xml does not register the filter ")
					.append(FILTER_NAME)
					.append("; a customisation or 2Pack package has replaced it");
		}

		if (!zkInterceptorRegistered(event)) {
			problems.append("\n  - WEB-INF/zk.xml does not register ")
					.append(CohortDecisionInterceptor.class.getName())
					.append("; a customisation or 2Pack package has replaced it");
		}

		if (problems.length() > 0) {
			// Fatal. A mixed deployment is worse than no deployment: it looks
			// healthy and routes incorrectly.
			throw new IllegalStateException(
					"The Phase 5e cohort bridge is incompletely deployed:"
					+ problems);
		}

		CohortBridge bridge = CohortBridge.initialise();
		if (bridge.routingPossible()) {
			log.info("Phase 5e cohort routing is armed on this context");
		} else {
			log.log(Level.INFO,
					"Phase 5e cohort routing is inactive: {0}. Every session stays "
					+ "on the legacy runtime.",
					bridge.keyFailure());
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		CohortBridge.shutdown();
	}

	/**
	 * Reads the deployed {@code WEB-INF/zk.xml} rather than asking ZK.
	 *
	 * <p>ZK 3.6's {@code Configuration} does not expose its registered event
	 * interceptors, and the web application is not fully started when a context
	 * listener runs, so asking the framework would either be impossible or
	 * answer about a half-built configuration. The deployed descriptor is the
	 * artifact a customisation actually replaces, so it is also the right thing
	 * to assert on.
	 */
	private boolean zkInterceptorRegistered(ServletContextEvent event) {
		try (java.io.InputStream descriptor =
				event.getServletContext().getResourceAsStream("/WEB-INF/zk.xml")) {
			if (descriptor == null) {
				return false;
			}
			String content = new String(descriptor.readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			return content.contains(CohortDecisionInterceptor.class.getName());
		} catch (java.io.IOException unreadable) {
			log.log(Level.SEVERE, "WEB-INF/zk.xml could not be read", unreadable);
			return false;
		}
	}
}
