package org.adempiere.web.route;

/**
 * The reviewed Phase 5e public affinity unit: exactly the request classes the
 * router may proxy to the modern runtime, and nothing else.
 *
 * <p>The set is closed. A request that does not classify into a proxyable class
 * is refused rather than forwarded, which is what keeps Phase 5f's non-ZK
 * contexts, the DSP theme and the timeline feed out of Phase 5e by construction
 * rather than by intention.
 */
public enum PublicRouteClass {

	/** {@code GET|HEAD} on the context root; the modern bootstrap entry. */
	CONTEXT_ROOT(true),

	/** {@code GET|HEAD} on a {@code *.zul} or {@code *.zhtml} page. */
	ZK_PAGE(true),

	/** {@code POST} on a {@code *.zul} page: a URL-rewritten public form. */
	ZK_PUBLIC_FORM(true),

	/** {@code GET|HEAD} under {@code /zkau/web/}: ZK client resources. */
	ZK_RESOURCE(true),

	/** {@code GET|POST} on the ZK asynchronous update engine, incl. polling. */
	ZK_AU(true),

	/** {@code GET|HEAD} on a reviewed static asset prefix. */
	STATIC_ASSET(true),

	/** Anything else. Never proxied. */
	UNKNOWN(false);

	private final boolean proxyable;

	PublicRouteClass(boolean proxyable) {
		this.proxyable = proxyable;
	}

	/** Whether the router may forward this class to the modern runtime. */
	public boolean proxyable() {
		return proxyable;
	}
}
