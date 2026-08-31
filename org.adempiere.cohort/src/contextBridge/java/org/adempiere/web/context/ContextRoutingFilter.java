package org.adempiere.web.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.route.ContextRoutingDecision;
import org.adempiere.web.route.ContextRoutingPolicy;
import org.adempiere.web.route.ContextSessionAffinity;
import org.adempiere.web.route.ContextSwitch;
import org.adempiere.web.route.DeferredResponseBuffer;
import org.adempiere.web.route.LoopbackProxy;
import org.adempiere.web.route.ProxyResult;
import org.adempiere.web.route.PublicRouteClass;
import org.adempiere.web.route.RedirectDescriptor;
import org.adempiere.web.route.SessionPathParameters;

/**
 * Javax Servlet adapter that switches one complete non-{@code /webui} context.
 */
public final class ContextRoutingFilter implements Filter {

	private static final java.util.logging.Logger LOG =
			java.util.logging.Logger.getLogger(
					ContextRoutingFilter.class.getName());

	/**
	 * Stable prefix the Phase 5f route smoke harvests out of the Tomcat 9
	 * container log. A fail-closed proxy answers 502 with no indication of
	 * why, so without this line a route failure cannot be attributed to the
	 * servlet, the container error page or the routing decision.
	 *
	 * <p>Everything appended here is already sanitized by
	 * {@link org.adempiere.web.route.RedirectDescriptor} or is a non-secret
	 * scalar. No header value, cookie, credential or handoff ticket is logged.
	 */
	static final String FAILURE_LOG_PREFIX = "PHASE5F-PROXY-FAIL";

	private ContextRoutingPolicy policy;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		ContextRoutingBridge bridge = ContextRoutingBridge.current();
		if (bridge == null) {
			throw new ServletException(
					"ContextRoutingStartupListener must initialise before the filter");
		}
		policy = ContextRoutingPolicy.forContext(
				filterConfig.getServletContext().getContextPath());
		if (policy == null) {
			throw new ServletException("No reviewed policy exists for this context");
		}
	}

	@Override
	public void doFilter(
			ServletRequest servletRequest,
			ServletResponse servletResponse,
			FilterChain chain) throws IOException, ServletException {
		if (!(servletRequest instanceof HttpServletRequest)
				|| !(servletResponse instanceof HttpServletResponse)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;
		if (reservedHeader(request)) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		if (SessionPathParameters.carriesSessionParameter(
				request.getRequestURI())) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		HttpSession session = request.getSession(false);
		boolean recordedModern = session != null && Boolean.TRUE.equals(
				session.getAttribute(ContextSessionAffinity.MODERN_MARKER));
		ContextSessionAffinity affinity = recordedModern
				? (ContextSessionAffinity) session.getAttribute(
						ContextSessionAffinity.ATTRIBUTE)
				: null;
		ContextRoutingBridge bridge = ContextRoutingBridge.current();
		ContextSwitch current = session == null
				? bridge.currentSwitch(policy)
				: new ContextSwitch(true, false, java.util.List.of());
		ContextRoutingDecision.Action action = ContextRoutingDecision.decide(
				session != null, recordedModern, affinity,
				current.valid() && current.enabled(), bridge.deploymentId());

		switch (action) {
			case LEGACY:
				chain.doFilter(request, response);
				return;
			case FAIL:
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			case INVALIDATE:
				invalidate(request, session, response);
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			case MODERN_SESSIONLESS:
			case MODERN_SESSION:
				break;
			default:
				throw new IllegalStateException("Unhandled action " + action);
		}

		String pathInside = pathInside(request);
		if (policy.confidential(pathInside) && !request.isSecure()) {
			response.sendRedirect(httpsLocation(request));
			return;
		}
		if (!bridge.routingPossible()) {
			if (affinity != null) {
				affinity.failed("backend-unavailable");
			}
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return;
		}

		try (BufferedResponseAdapter buffered =
				new BufferedResponseAdapter(policy.responseLimit())) {
			String sentModernSessionId =
					affinity == null ? null : affinity.modernSessionId();
			ProxyResult result = proxy(bridge.backend()).proxy(
					new RequestAdapter(request), buffered,
					PublicRouteClass.UNKNOWN, pathInside,
					sentModernSessionId,
					null, null, policy);
			if (!result.completed()) {
				LOG.severe(FAILURE_LOG_PREFIX
						+ " context=" + policy.contextPath()
						+ " method=" + request.getMethod()
						+ " path=" + RedirectDescriptor.describe(pathInside)
						+ " reason=" + result.diagnostic());
				if (affinity != null) {
					affinity.failed(result.failure());
				}
				if (!response.isCommitted()) {
					response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
				}
				return;
			}
			if (result.sessionEnded()) {
				invalidate(request, session, response);
				if (!response.isCommitted()) {
					response.sendRedirect(contextRoot(request));
				}
				return;
			}
			if (session == null && result.modernSessionId() != null) {
				session = request.getSession(true);
				session.setMaxInactiveInterval(policy.sessionTimeout());
				session.setAttribute(
						ContextSessionAffinity.MODERN_MARKER, Boolean.TRUE);
				session.setAttribute(ContextSessionAffinity.ATTRIBUTE,
						new ContextSessionAffinity(
								bridge.deploymentId(), result.modernSessionId()));
				publicSessionCookie(request, response, session.getId());
			} else if (affinity != null && result.modernSessionId() != null
					&& !affinity.updateModernSessionId(
							sentModernSessionId, result.modernSessionId())) {
				affinity.failed("modern-session-rotation-race");
				response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
				return;
			}
			buffered.flushTo(response);
		}
	}

	LoopbackProxy proxy(String backend) {
		return new LoopbackProxy(backend);
	}

	private static void invalidate(
			HttpServletRequest request,
			HttpSession session,
			HttpServletResponse response) {
		if (session != null) {
			try {
				session.invalidate();
			} catch (IllegalStateException alreadyInvalid) {
				// Container lifecycle won the race.
			}
		}
		String context = request.getContextPath();
		response.setHeader("Set-Cookie",
				"JSESSIONID=; Path="
				+ (context == null || context.isEmpty() ? "/" : context)
				+ "; Max-Age=0; HttpOnly; SameSite=Lax");
	}

	private static void publicSessionCookie(
			HttpServletRequest request,
			HttpServletResponse response,
			String id) {
		String context = request.getContextPath();
		response.setHeader("Set-Cookie",
				"JSESSIONID=" + id + "; Path="
				+ (context == null || context.isEmpty() ? "/" : context)
				+ "; HttpOnly; SameSite=Lax"
				+ (request.isSecure() ? "; Secure" : ""));
	}

	private static String pathInside(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String context = request.getContextPath();
		if (uri == null || uri.isEmpty()) {
			return "/";
		}
		if (context != null && !context.isEmpty() && uri.startsWith(context)) {
			uri = uri.substring(context.length());
		}
		return uri.isEmpty() ? "/" : uri;
	}

	private static String contextRoot(HttpServletRequest request) {
		String context = request.getContextPath();
		return context == null || context.isEmpty() ? "/" : context + "/";
	}

	private static String httpsLocation(HttpServletRequest request) {
		StringBuilder result = new StringBuilder("https://")
				.append(request.getServerName());
		int port = Integer.getInteger(
				"adempiere.phase5f.publicHttpsPort",
				request.getServerPort() == 80 ? 443 : request.getServerPort());
		if (port != 443) {
			result.append(':').append(port);
		}
		result.append(request.getRequestURI());
		if (request.getQueryString() != null) {
			result.append('?').append(request.getQueryString());
		}
		return result.toString();
	}

	private static boolean reservedHeader(HttpServletRequest request) {
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			if (HandoffProtocol.reserved(names.nextElement())) {
				return true;
			}
		}
		return false;
	}

	private static final class RequestAdapter implements LoopbackProxy.Request {
		private final HttpServletRequest request;

		private RequestAdapter(HttpServletRequest request) {
			this.request = request;
		}

		public String method() { return request.getMethod(); }
		public String contextPath() { return request.getContextPath(); }
		public String queryString() { return request.getQueryString(); }
		public Iterable<String> headerNames() {
			return iterable(request.getHeaderNames());
		}
		public Iterable<String> headers(String name) {
			return iterable(request.getHeaders(name));
		}
		public long contentLength() { return request.getContentLengthLong(); }
		public InputStream inputStream() throws IOException {
			return request.getInputStream();
		}
		public String scheme() { return request.getScheme(); }
		public String serverName() { return request.getServerName(); }
		public int serverPort() { return request.getServerPort(); }
	}

	private static final class BufferedResponseAdapter
			implements LoopbackProxy.Response, AutoCloseable {
		private int status;
		private final List<String[]> headers = new ArrayList<>();
		private final DeferredResponseBuffer body;

		private BufferedResponseAdapter(long maximumBytes) {
			String catalinaBase = System.getProperty("catalina.base", ".");
			body = new DeferredResponseBuffer(
					Path.of(catalinaBase, "work", "phase5f-response-spool"),
					maximumBytes);
		}

		public void status(int value) { status = value; }
		public void header(String name, String value) {
			headers.add(new String[] {name, value});
		}
		public OutputStream outputStream() { return body.outputStream(); }

		private void flushTo(HttpServletResponse response) throws IOException {
			response.setStatus(status);
			for (String[] header : headers) {
				response.addHeader(header[0], header[1]);
			}
			if (body.size() > 0) {
				body.commitTo(response.getOutputStream());
			}
		}

		public void close() throws IOException {
			body.close();
		}
	}

	private static <T> Iterable<T> iterable(Enumeration<T> values) {
		return values == null ? Collections.emptyList() : () -> values.asIterator();
	}
}
