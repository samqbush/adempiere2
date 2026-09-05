package org.adempiere.webui.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.adempiere.web.handoff.HandoffProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Tag("UnitTest")
@DisplayName("Phase 5e modern handoff filter")
class CohortHandoffFilterTest {

	private CohortHandoffFilter filter;

	@AfterEach
	void restoreConfiguration() {
		if (filter != null) {
			filter.destroy();
		}
		System.clearProperty(CohortHandoffFilter.KEY_PROPERTY);
	}

	@Test
	@DisplayName("a loopback request for an ended routed session repeats END")
	void endedRoutedSessionWinsTheConcurrentLogoutRace(@TempDir Path directory)
			throws Exception {
		filter = armedFilter(directory);
		rememberEnded(filter, "MODERN-1");
		HttpServletRequest request =
				request("127.0.0.1", "ROTATED", null, "MODERN-1");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).setHeader(
				HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
		verify(response).setStatus(HttpServletResponse.SC_RESET_CONTENT);
		verify(response, never()).sendError(anyInt());
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("a concurrently invalidated ended session still repeats END")
	void invalidatedSessionUsesTheEndedRecord(@TempDir Path directory)
			throws Exception {
		filter = armedFilter(directory);
		rememberEnded(filter, "MODERN-1");
		HttpSession invalidated = mock(HttpSession.class);
		when(invalidated.getAttribute(CohortHandoff.ENDED_ATTRIBUTE))
				.thenThrow(new IllegalStateException("already invalidated"));
		HttpServletRequest request =
				request("127.0.0.1", "ROTATED", invalidated, "MODERN-1");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).setHeader(
				HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
		verify(response).setStatus(HttpServletResponse.SC_RESET_CONTENT);
		verify(response, never()).sendError(anyInt());
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("an unknown loopback binding remains forbidden")
	void backendRestartDoesNotLookLikeLogout(@TempDir Path directory)
			throws Exception {
		filter = armedFilter(directory);
		HttpServletRequest request =
				request("127.0.0.1", "ROTATED", null, "UNKNOWN");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
		verify(response, never()).setHeader(
				HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("an untrusted stale binding remains forbidden")
	void nonLoopbackBindingCannotClaimAnEndedSession(@TempDir Path directory)
			throws Exception {
		filter = armedFilter(directory);
		rememberEnded(filter, "MODERN-1");
		HttpServletRequest request =
				request("192.0.2.10", "ROTATED", null, "MODERN-1");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
		verify(response, never()).setHeader(
				HandoffProtocol.END_HEADER, HandoffProtocol.END_VALUE);
		verify(chain, never()).doFilter(any(), any());
	}

	private static HttpServletRequest request(
			String remoteAddress,
			String boundSession,
			HttpSession session,
			String requestedSessionId) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(HandoffProtocol.TICKET_HEADER)).thenReturn(null);
		when(request.getHeader(HandoffProtocol.SESSION_HEADER))
				.thenReturn(boundSession);
		when(request.getSession(false)).thenReturn(session);
		when(request.getRequestedSessionId()).thenReturn(requestedSessionId);
		when(request.getRemoteAddr()).thenReturn(remoteAddress);
		return request;
	}

	private static void rememberEnded(
			CohortHandoffFilter filter, String sessionId) throws Exception {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(CohortHandoff.ENDED_ATTRIBUTE))
				.thenReturn(Boolean.TRUE);
		when(session.getId()).thenReturn(sessionId);
		HttpServletRequest request =
				request("127.0.0.1", "BOUND", session, sessionId);

		filter.doFilter(
				request,
				mock(HttpServletResponse.class),
				mock(FilterChain.class));
	}

	private static CohortHandoffFilter armedFilter(Path directory)
			throws Exception {
		byte[] keyMaterial = new byte[32];
		for (int index = 0; index < keyMaterial.length; index++) {
			keyMaterial[index] = (byte) index;
		}
		Path key = directory.resolve("handoff.key");
		Files.write(key, keyMaterial);
		Files.setPosixFilePermissions(
				key, PosixFilePermissions.fromString("rw-------"));
		System.setProperty(CohortHandoffFilter.KEY_PROPERTY, key.toString());
		CohortHandoffFilter filter = new CohortHandoffFilter();
		filter.init(mock(FilterConfig.class));
		return filter;
	}
}
