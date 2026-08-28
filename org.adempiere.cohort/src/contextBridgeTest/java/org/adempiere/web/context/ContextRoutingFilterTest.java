package org.adempiere.web.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.sun.net.httpserver.HttpServer;
import org.adempiere.web.route.ContextSessionAffinity;
import org.adempiere.web.route.ContextSwitch;
import org.adempiere.web.route.LoopbackProxy;
import org.adempiere.web.route.ProxyResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ContextRoutingFilterTest {

	@AfterEach
	void shutdown() {
		ContextRoutingBridge.shutdown();
	}

	@Test
	void sessionlessDisabledDoesNotCreateSession() throws Exception {
		ContextRoutingFilter filter = filter("/wstore", false);
		HttpServletRequest request = request("/wstore", "/wstore/index.jsp");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getSession(false)).thenReturn(null);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(request, never()).getSession(true);
	}

	@Test
	void preExistingSessionStaysLegacyAfterEnable() throws Exception {
		ContextRoutingFilter filter = filter("/wstore", true);
		HttpServletRequest request = request("/wstore", "/wstore/index.jsp");
		HttpSession session = mock(HttpSession.class);
		when(request.getSession(false)).thenReturn(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(503);
	}

	@Test
	void liveModernSessionIsInvalidatedAcrossDeployment() throws Exception {
		ContextRoutingFilter filter = filter("/wstore", true);
		HttpServletRequest request = request("/wstore", "/wstore/index.jsp");
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ContextSessionAffinity.MODERN_MARKER))
				.thenReturn(Boolean.TRUE);
		when(session.getAttribute(ContextSessionAffinity.ATTRIBUTE))
				.thenReturn(new ContextSessionAffinity("OLD", "MODERN"));
		when(request.getSession(false)).thenReturn(session);
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter.doFilter(request, response, mock(FilterChain.class));

		verify(session).invalidate();
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	void confidentialHttpRouteRedirectsWithoutProxyOrSession() throws Exception {
		ContextRoutingFilter filter = filter("/wstore", true);
		HttpServletRequest request = request("/wstore", "/wstore/orderServlet");
		when(request.getSession(false)).thenReturn(null);
		when(request.isSecure()).thenReturn(false);
		when(request.getScheme()).thenReturn("http");
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter.doFilter(request, response, mock(FilterChain.class));

		verify(response).sendRedirect("https://public.example/wstore/orderServlet");
		verify(request, never()).getSession(true);
	}

	@Test
	void confidentialPathInfoDescendantAlsoRedirectsAtPublicIngress()
			throws Exception {
		ContextRoutingFilter filter = filter("/wstore", true);
		HttpServletRequest request =
				request("/wstore", "/wstore/login.jsp/account");
		when(request.getSession(false)).thenReturn(null);
		when(request.isSecure()).thenReturn(false);
		when(request.getScheme()).thenReturn("http");
		HttpServletResponse response = mock(HttpServletResponse.class);

		filter.doFilter(request, response, mock(FilterChain.class));

		verify(response).sendRedirect(
				"https://public.example/wstore/login.jsp/account");
		verify(request, never()).getSession(true);
	}

	@Test
	void exactUrlSessionParameterIsRejectedBeforeSessionLookup()
			throws Exception {
		assertUrlSessionRejected("/wstore/index.jsp;jsessionid=FORGED");
	}

	@Test
	void pathInfoUrlSessionParameterIsRejectedBeforeSessionLookup()
			throws Exception {
		assertUrlSessionRejected(
				"/wstore/login.jsp;jsessionid=FORGED/account");
	}

	private static void assertUrlSessionRejected(String uri) throws Exception {
		ContextRoutingFilter filter = filter("/wstore", true);
		HttpServletRequest request = request("/wstore", uri);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
		verify(request, never()).getSession(false);
		verify(request, never()).getSession(true);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void laterBackendSessionRotationUpdatesThePinnedAffinity() throws Exception {
		List<String> inboundCookies = new ArrayList<>();
		HttpServer server = HttpServer.create(
				new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/wstore/index.jsp", exchange -> {
			inboundCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
			exchange.getResponseHeaders().add(
					"Set-Cookie",
					"JSESSIONID_WSTORE=ROTATED; Path=/wstore; HttpOnly");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(
					new byte[0]);
			exchange.close();
		});
		server.start();
		try {
			ContextRoutingFilter filter = filter(
					"/wstore", true,
					"http://127.0.0.1:" + server.getAddress().getPort());
			ContextSessionAffinity affinity =
					new ContextSessionAffinity("CURRENT", "ORIGINAL");
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(ContextSessionAffinity.MODERN_MARKER))
					.thenReturn(Boolean.TRUE);
			when(session.getAttribute(ContextSessionAffinity.ATTRIBUTE))
					.thenReturn(affinity);
			HttpServletRequest request =
					request("/wstore", "/wstore/index.jsp");
			when(request.getSession(false)).thenReturn(session);

			filter.doFilter(
					request, mock(HttpServletResponse.class),
					mock(FilterChain.class));
			assertEquals("ROTATED", affinity.modernSessionId());
			filter.doFilter(
					request, mock(HttpServletResponse.class),
					mock(FilterChain.class));

			assertEquals(List.of(
					"JSESSIONID_WSTORE=ORIGINAL",
					"JSESSIONID_WSTORE=ROTATED"), inboundCookies);
		} finally {
			server.stop(0);
		}
	}

	private static ContextRoutingFilter filter(String context, boolean enabled)
			throws Exception {
		return filter(context, enabled, "http://127.0.0.1:9191");
	}

	private static ContextRoutingFilter filter(
			String context, boolean enabled, String backend) throws Exception {
		ContextRoutingBridge.install(backend, "CURRENT",
				key -> new ContextSwitch(true, enabled, List.of()));
		ServletContext servletContext = mock(ServletContext.class);
		when(servletContext.getContextPath()).thenReturn(context);
		FilterConfig config = mock(FilterConfig.class);
		when(config.getServletContext()).thenReturn(servletContext);
		ContextRoutingFilter filter = new ContextRoutingFilter();
		filter.init(config);
		return filter;
	}

	private static HttpServletRequest request(String context, String uri)
			throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContextPath()).thenReturn(context);
		when(request.getRequestURI()).thenReturn(uri);
		when(request.getMethod()).thenReturn("GET");
		when(request.getHeaderNames()).thenReturn(new Vector<String>().elements());
		when(request.getScheme()).thenReturn("https");
		when(request.getServerName()).thenReturn("public.example");
		when(request.getServerPort()).thenReturn(443);
		when(request.getInputStream()).thenReturn(new javax.servlet.ServletInputStream() {
			private final ByteArrayInputStream input =
					new ByteArrayInputStream(new byte[0]);
			public int read() { return input.read(); }
			public boolean isFinished() { return input.available() == 0; }
			public boolean isReady() { return true; }
			public void setReadListener(javax.servlet.ReadListener listener) { }
		});
		return request;
	}
}
