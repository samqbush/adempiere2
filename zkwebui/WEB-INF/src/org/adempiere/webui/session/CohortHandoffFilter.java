/**
 * Copyright (C) 2003-2026, ADempiere modernization Phase 5e.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.adempiere.webui.session;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.logging.Level;

import org.adempiere.web.handoff.HandoffKey;
import org.adempiere.web.handoff.HandoffKeyException;
import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.handoff.HandoffResult;
import org.adempiere.web.handoff.HandoffTicketCodec;
import org.adempiere.web.handoff.ReplayCache;
import org.compiere.util.CLogger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The modern runtime's half of the Phase 5e handoff.
 *
 * <p>This filter is the only way a session can become authenticated in the
 * routed lane, and it is inert in the direct lane. Which of the two applies is
 * decided entirely by whether a handoff key is configured:
 *
 * <dl>
 *   <dt>No key configured</dt>
 *   <dd>Phase 5d behaviour, unchanged: the ordinary ZK login form serves
 *       everybody. A request that carries anything in the reserved internal
 *       header namespace is still refused, because in this mode nothing may
 *       present a ticket at all.</dd>
 *
 *   <dt>Key configured and valid</dt>
 *   <dd>Routed behaviour. A session may only be created by a first request that
 *       arrives from loopback carrying a valid, unexpired, unused ticket bound
 *       to the presenting Tomcat 9 session. Every other request on an
 *       unbootstrapped session is refused.</dd>
 *
 *   <dt>Key configured and invalid</dt>
 *   <dd>Deployment fails. {@link #init} throws, so Tomcat refuses to start the
 *       context rather than serving a modern UI whose handoff cannot be
 *       verified.</dd>
 * </dl>
 */
public class CohortHandoffFilter implements Filter {

	private static final CLogger log = CLogger.getCLogger(CohortHandoffFilter.class);

	/** System property naming the shared handoff key file. */
	public static final String KEY_PROPERTY = "adempiere.phase5e.handoffKey";

	/** Environment variable equivalent of {@link #KEY_PROPERTY}. */
	public static final String KEY_ENVIRONMENT = "ADEMPIERE_PHASE5E_HANDOFF_KEY";

	private final HandoffTicketCodec codec = new HandoffTicketCodec();
	private final ReplayCache replayCache = new ReplayCache();
	private HandoffKey key;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		String configured = System.getProperty(KEY_PROPERTY);
		if (configured == null || configured.isBlank()) {
			configured = System.getenv(KEY_ENVIRONMENT);
		}
		if (configured == null || configured.isBlank()) {
			log.info("Phase 5e handoff is not configured on this context; the "
					+ "ordinary login form serves every session");
			return;
		}
		try {
			key = HandoffKey.load(Paths.get(configured));
		} catch (HandoffKeyException rejected) {
			// Fatal on purpose. A configured-but-unusable key means the operator
			// intended routed operation; serving an unverifiable modern UI
			// instead would be worse than not deploying.
			throw new ServletException(
					"The Phase 5e handoff key is unusable: " + rejected.getMessage(),
					rejected);
		}
		log.info("Phase 5e handoff verification is armed on this context");
	}

	@Override
	public void destroy() {
		replayCache.clear();
		key = null;
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

		String ticket = request.getHeader(HandoffProtocol.TICKET_HEADER);
		String boundSession = request.getHeader(HandoffProtocol.SESSION_HEADER);

		if (key == null) {
			if (ticket != null || boundSession != null || carriesReserved(request)) {
				log.severe("A request presented a Phase 5e internal header on a "
						+ "context with no handoff key");
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		HttpSession existing = request.getSession(false);
		if (CohortHandoff.ended(existing)) {
			// The routed session was logged out on this runtime. Destroying it
			// here - before the chain, so nothing is committed yet - is what
			// makes a routed logout a real server-side destruction on BOTH
			// runtimes: this invalidation runs the modern SessionManagerListener
			// cleanup, and the signalled router invalidates its own Tomcat 9
			// session, which runs the legacy one.
			log.info("Destroying a logged-out Phase 5e routed session");
			try {
				existing.invalidate();
			} catch (IllegalStateException alreadyGone) {
				log.fine("The logged-out routed session was already destroyed");
			}
			response.setHeader(HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
			response.setStatus(HttpServletResponse.SC_RESET_CONTENT);
			return;
		}
		if (CohortHandoff.bootstrapped(existing)) {
			if (ticket != null) {
				// A second ticket on an already bootstrapped session is either a
				// retry that must not create a second identity, or misuse.
				log.severe("A Phase 5e ticket was presented to an already "
						+ "bootstrapped session");
				response.sendError(HttpServletResponse.SC_CONFLICT);
				return;
			}
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		if (ticket == null) {
			if (boundSession != null && !boundSession.isBlank()
					&& loopback(request.getRemoteAddr())) {
				// A routed request can race the request that destroys the modern
				// session during logout. The public router preserves the bound
				// Tomcat 9 session on every internal request, so this exact
				// no-session case can complete the existing END handshake
				// instead of exposing Tomcat's 403 page to the browser.
				log.info("A routed request reached an ended Phase 5e session");
				response.setHeader(
						HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
				response.setStatus(HttpServletResponse.SC_RESET_CONTENT);
				return;
			}
			// Fail closed: in the routed lane the router is the only way in.
			log.severe("An unbootstrapped modern session made a request with no "
					+ "Phase 5e ticket");
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		if (!loopback(request.getRemoteAddr())) {
			log.severe("A Phase 5e ticket was presented from a non-loopback peer");
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		if (boundSession == null || boundSession.isBlank()) {
			log.severe("A Phase 5e ticket was presented without its session binding");
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		HandoffResult result = codec.decode(
				ticket, key, boundSession, System.currentTimeMillis(), replayCache);
		if (!result.accepted()) {
			log.severe("A Phase 5e ticket was refused: " + result.rejection());
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		// Only now is a session created, so a refused ticket leaves no trace.
		HttpSession session = request.getSession(true);
		String failure = CohortHandoff.seed(session, result.ticket().identity());
		if (failure != null) {
			log.log(Level.SEVERE, "The Phase 5e handoff was verified but not "
					+ "applied: {0}", failure);
			session.invalidate();
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		chain.doFilter(servletRequest, servletResponse);
	}

	private static boolean carriesReserved(HttpServletRequest request) {
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			if (HandoffProtocol.reserved(names.nextElement())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the peer is on this host.
	 *
	 * <p>The modern connector is already bound to {@code 127.0.0.1}, so this is
	 * a second, independent statement of the same rule rather than the only one:
	 * a future deployment that widened the connector by accident would still not
	 * accept a ticket from off-host.
	 */
	private static boolean loopback(String remoteAddress) {
		if (remoteAddress == null || remoteAddress.isBlank()) {
			return false;
		}
		try {
			return InetAddress.getByName(remoteAddress).isLoopbackAddress();
		} catch (UnknownHostException unresolvable) {
			return false;
		}
	}
}
