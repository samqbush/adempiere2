package org.adempiere.web.cohort;

/**
 * The two web runtimes a Phase 5e session can be served by.
 *
 * <p>The value is decided once per session, after ordinary authentication and
 * role selection, and never changes for that session. A configuration change
 * therefore only affects sessions created after it.
 */
public enum CohortRuntime {

	/** The frozen ZK 3.6 application served directly by Tomcat 9. */
	LEGACY,

	/** The ZK CE 10 Jakarta application served by loopback Tomcat 10. */
	MODERN
}
