package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpServer;
import org.adempiere.web.handoff.HandoffProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Focused transport test for the Servlet-free proxy seam. */
@Tag("UnitTest")
@DisplayName("Framework-neutral loopback proxy")
class LoopbackProxyTest {

	@Test
	@DisplayName("the routed session binding survives after bootstrap")
	void routedBindingIsSentWithoutAnotherTicket() throws Exception {
		HttpServer server = HttpServer.create(
				new InetSocketAddress("127.0.0.1", 0), 0);
		java.util.concurrent.atomic.AtomicReference<String> binding =
				new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> ticket =
				new java.util.concurrent.atomic.AtomicReference<>();
		server.createContext("/webui/index.zul", exchange -> {
			binding.set(exchange.getRequestHeaders().getFirst(
					HandoffProtocol.SESSION_HEADER));
			ticket.set(exchange.getRequestHeaders().getFirst(
					HandoffProtocol.TICKET_HEADER));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try {
			ProxyResult result = new LoopbackProxy(
					"http://127.0.0.1:" + server.getAddress().getPort()).proxy(
							new Request(), new CapturedResponse(),
							PublicRouteClass.ZK_PAGE, "/index.zul",
							"MODERN", null, "ROTATED");

			assertTrue(result.completed());
			assertEquals("ROTATED", binding.get());
			assertNull(ticket.get(),
					"ordinary routed requests must not replay the bootstrap ticket");
		} finally {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("the core consumes the internal cookie and rewrites redirects")
	void proxyPolicyDoesNotDependOnServlet() throws Exception {
		HttpServer server = HttpServer.create(
				new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/webui/index.zul", exchange -> {
			exchange.getResponseHeaders().add(
					"Set-Cookie", "JSESSIONID=MODERN; Path=/webui; HttpOnly");
			exchange.getResponseHeaders().add("Location",
					"http://127.0.0.1:" + server.getAddress().getPort()
							+ "/webui/next.zul");
			exchange.getResponseHeaders().add("Server", "internal");
			byte[] body = "proxied".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(302, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String backend =
					"http://127.0.0.1:" + server.getAddress().getPort();
			CapturedResponse response = new CapturedResponse();
			ProxyResult result = new LoopbackProxy(backend).proxy(
					new Request(), response, PublicRouteClass.ZK_PAGE,
					"/index.zul", null, null, null);

			assertEquals(302, response.status);
			assertEquals("MODERN", result.modernSessionId());
			assertEquals("https://public.example/webui/next.zul",
					response.headers.get("Location").get(0));
			assertFalse(response.headers.containsKey("Set-Cookie"));
			assertFalse(response.headers.containsKey("Server"));
			assertEquals("proxied", response.body.toString(StandardCharsets.UTF_8));
		} finally {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("an alternate loopback Location fails closed")
	void alternateLoopbackLocationIsNotExposed() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/wstore/index.jsp", exchange -> {
				exchange.getResponseHeaders().add(
						"Location", "http://[::1]:9191/private");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
			server.start();
			try {
				CapturedResponse response = new CapturedResponse();
				ProxyResult result = new LoopbackProxy(
						"http://127.0.0.1:" + server.getAddress().getPort()).proxy(
								new Request() {
									@Override
									public String contextPath() {
										return "/wstore";
									}
								},
								response, PublicRouteClass.UNKNOWN, "/index.jsp",
								null, null, null,
								ContextRoutingPolicy.forContext("/wstore"));
				assertFalse(result.completed());
				assertEquals("internal-location-leak", result.failure());
				assertFalse(response.headers.containsKey("Location"));
			} finally {
				server.stop(0);
		}
	}

	@Test
	@DisplayName("a Location that is not a valid URI is still rewritten, not failed closed")
	void unparseableBackendLocationIsRewritten() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			int port = server.getAddress().getPort();
			server.createContext("/index.jsp", exchange -> {
				exchange.getResponseHeaders().add(
						"Location",
						"http://127.0.0.1:" + port + "/x.jsp?msg=a b");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
			server.start();
			try {
				CapturedResponse response = new CapturedResponse();
				ProxyResult result = new LoopbackProxy(
						"http://127.0.0.1:" + port).proxy(
								new Request() {
									@Override
									public String contextPath() {
										return "";
									}
								},
								response, PublicRouteClass.UNKNOWN, "/index.jsp",
								null, null, null,
								ContextRoutingPolicy.forContext("/"));
				assertTrue(result.completed());
				assertEquals(
						"https://public.example/x.jsp?msg=a b",
						response.headers.get("Location").get(0));
			} finally {
				server.stop(0);
		}
	}

	@Test
	@DisplayName("a same-backend Location that omits the internal port is rewritten")
	void omittedBackendPortIsRewritten() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/index.jsp", exchange -> {
				exchange.getResponseHeaders().add(
						"Location", "http://127.0.0.1/admin/");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
			server.start();
			try {
				CapturedResponse response = new CapturedResponse();
				ProxyResult result = new LoopbackProxy(
						"http://127.0.0.1:" + server.getAddress().getPort()).proxy(
								new Request() {
									@Override
									public String contextPath() {
										return "";
									}
								},
								response, PublicRouteClass.UNKNOWN, "/index.jsp",
								null, null, null,
								ContextRoutingPolicy.forContext("/"));
				assertTrue(result.completed());
				assertEquals(
						"https://public.example/admin/",
						response.headers.get("Location").get(0));
			} finally {
				server.stop(0);
		}
	}

	@Test
	@DisplayName("a Location that only shares a textual prefix with the backend fails closed")
	void backendPrefixWithoutOriginBoundaryIsNotExposed() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			int port = server.getAddress().getPort();
			server.createContext("/index.jsp", exchange -> {
				// The backend origin is normalized without a trailing slash, so
				// this value passes a bare startsWith. Stripping the prefix
				// would leave "@evil.example/x", which appended to the public
				// origin is read by a browser as userinfo: the ingress would
				// emit an open redirect to evil.example under its own origin.
				exchange.getResponseHeaders().add(
						"Location",
						"http://127.0.0.1:" + port + "@evil.example/x");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
			server.start();
			try {
				CapturedResponse response = new CapturedResponse();
				ProxyResult result = new LoopbackProxy(
						"http://127.0.0.1:" + port).proxy(
								new Request() {
									@Override
									public String contextPath() {
										return "";
									}
								},
								response, PublicRouteClass.UNKNOWN, "/index.jsp",
								null, null, null,
								ContextRoutingPolicy.forContext("/"));
				assertFalse(result.completed());
				assertEquals("internal-location-leak", result.failure());
				assertFalse(response.headers.containsKey("Location"));
			} finally {
				server.stop(0);
		}
	}

	@Test
	@DisplayName("a same-host Location on a different explicit port still fails closed")
	void foreignLoopbackPortIsStillNotExposed() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			int port = server.getAddress().getPort();
			server.createContext("/index.jsp", exchange -> {
				exchange.getResponseHeaders().add(
						"Location", "http://127.0.0.1:" + (port + 1)
								+ "/private;jsessionid=SECRET1?token=SECRET2");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
			server.start();
			try {
				CapturedResponse response = new CapturedResponse();
				ProxyResult result = new LoopbackProxy(
						"http://127.0.0.1:" + port).proxy(
								new Request() {
									@Override
									public String contextPath() {
										return "";
									}
								},
								response, PublicRouteClass.UNKNOWN, "/index.jsp",
								null, null, null,
								ContextRoutingPolicy.forContext("/"));
				assertFalse(result.completed());
				assertEquals("internal-location-leak", result.failure());
				assertFalse(response.headers.containsKey("Location"));
				// Accepting a port-omitted same-host Location must not become
				// "accept any loopback Location": a different explicit port is
				// a different origin, and the Phase 5e isolation guarantee is
				// that it never reaches the browser. The diagnostic records
				// what was rejected, and does so through RedirectDescriptor so
				// that the permanent log cannot carry a session or a query
				// value.
				assertTrue(result.diagnostic().contains("port=" + (port + 1)));
				assertTrue(result.diagnostic().contains("/private;jsessionid="));
				assertFalse(result.diagnostic().contains("SECRET1"));
				assertFalse(result.diagnostic().contains("SECRET2"));
			} finally {
				server.stop(0);
		}
	}

	@Test
	@DisplayName("wstore application cookies round-trip without exposing internal JSESSIONID")
	void wstoreApplicationCookieIsIsolated() throws Exception {
			HttpServer server = HttpServer.create(
					new InetSocketAddress("127.0.0.1", 0), 0);
			java.util.concurrent.atomic.AtomicReference<String> inbound =
					new java.util.concurrent.atomic.AtomicReference<>();
			server.createContext("/wstore/index.jsp", exchange -> {
				inbound.set(exchange.getRequestHeaders().getFirst("Cookie"));
				exchange.getResponseHeaders().add(
						"Set-Cookie",
						"JSESSIONID_WSTORE=INTERNAL; Path=/wstore");
				exchange.getResponseHeaders().add(
						"Set-Cookie", "AdempiereWebUser=remembered; Path=/");
				byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			try {
				String backend =
						"http://127.0.0.1:" + server.getAddress().getPort();
				CapturedResponse response = new CapturedResponse();
				Request request = new Request() {
					@Override
					public String contextPath() {
						return "/wstore";
					}

					@Override
					public Iterable<String> headers(String name) {
						return "Cookie".equals(name)
								? List.of("JSESSIONID=PUBLIC; AdempiereWebUser=old; Other=x")
								: super.headers(name);
					}
				};
				ProxyResult result = new LoopbackProxy(backend).proxy(
						request, response, PublicRouteClass.UNKNOWN, "/index.jsp",
						"INTERNAL-OLD", null, null,
						ContextRoutingPolicy.forContext("/wstore"));

				assertEquals("INTERNAL", result.modernSessionId());
				assertEquals(
						"JSESSIONID_WSTORE=INTERNAL-OLD; AdempiereWebUser=old",
						inbound.get());
				assertEquals(1, response.headers.get("Set-Cookie").size());
				assertTrue(response.headers.get("Set-Cookie").get(0)
						.startsWith("AdempiereWebUser=remembered; Path=/wstore"));
				assertFalse(response.headers.get("Set-Cookie").get(0)
						.contains("INTERNAL"));
			} finally {
				server.stop(0);
		}
	}

	@Test
	void persistentApplicationCookiePreservesLifetimeOverHttps()
			throws Exception {
		CookieOutcome outcome = applicationCookie(
				"AdempiereWebUser=remembered; Max-Age=86400; "
				+ "Expires=Fri, 28 Aug 2026 08:00:00 GMT; Path=/wrong; "
				+ "Secure; HttpOnly; SameSite=None",
				"https");

		assertTrue(outcome.result().completed());
		assertEquals(List.of(
				"AdempiereWebUser=remembered; Max-Age=86400; "
				+ "Expires=Fri, 28 Aug 2026 08:00:00 GMT; "
				+ "Path=/wstore; HttpOnly; SameSite=Lax; Secure"),
				outcome.response().headers.get("Set-Cookie"));
	}

	@Test
	void applicationCookieDeletionPreservesMaxAgeZeroOverHttp()
			throws Exception {
		CookieOutcome outcome = applicationCookie(
				"AdempiereWebUser=; Max-Age=0; "
				+ "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
				"http");

		assertTrue(outcome.result().completed());
		String cookie = outcome.response().headers.get("Set-Cookie").get(0);
		assertEquals(
				"AdempiereWebUser=; Max-Age=0; "
				+ "Expires=Thu, 1 Jan 1970 00:00:00 GMT; "
				+ "Path=/wstore; HttpOnly; SameSite=Lax",
				cookie);
		assertFalse(cookie.contains("; Secure"));
	}

	@Test
	void malformedUnsafeAndDuplicateApplicationAttributesFailClosed()
			throws Exception {
		for (String cookie : List.of(
				"AdempiereWebUser=x; Max-Age=tomorrow",
				"AdempiereWebUser=x; Expires=not-a-date",
				"AdempiereWebUser=x; Domain=example.com",
				"AdempiereWebUser=x; Max-Age=60; max-age=0",
				"AdempiereWebUser=x; Secure=true")) {
			CookieOutcome outcome = applicationCookie(cookie, "https");
			assertFalse(outcome.result().completed(), cookie);
			assertEquals(
					"invalid-application-cookie",
					outcome.result().failure(), cookie);
			assertFalse(
					outcome.response().headers.containsKey("Set-Cookie"),
					cookie);
		}
	}

	private static CookieOutcome applicationCookie(
			String setCookie, String scheme) throws Exception {
		HttpServer server = HttpServer.create(
				new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/wstore/index.jsp", exchange -> {
			exchange.getResponseHeaders().add("Set-Cookie", setCookie);
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			String backend =
					"http://127.0.0.1:" + server.getAddress().getPort();
			CapturedResponse response = new CapturedResponse();
			Request request = new Request() {
				public String contextPath() { return "/wstore"; }
				public String scheme() { return scheme; }
				public int serverPort() {
					return "https".equals(scheme) ? 443 : 80;
				}
			};
			ProxyResult result = new LoopbackProxy(backend).proxy(
					request, response, PublicRouteClass.UNKNOWN, "/index.jsp",
					null, null, null,
					ContextRoutingPolicy.forContext("/wstore"));
			return new CookieOutcome(result, response);
		} finally {
			server.stop(0);
		}
	}

	private record CookieOutcome(
			ProxyResult result, CapturedResponse response) {
	}

	private static class Request implements LoopbackProxy.Request {

		@Override
		public String method() {
			return "GET";
		}

		@Override
		public String contextPath() {
			return "/webui";
		}

		@Override
		public String queryString() {
			return null;
		}

		@Override
		public Iterable<String> headerNames() {
			return List.of("Accept", "Cookie");
		}

		@Override
		public Iterable<String> headers(String name) {
			return "Accept".equals(name)
					? List.of("text/html")
					: List.of("JSESSIONID=PUBLIC");
		}

		@Override
		public long contentLength() {
			return -1;
		}

		@Override
		public InputStream inputStream() {
			return new ByteArrayInputStream(new byte[0]);
		}

		@Override
		public String scheme() {
			return "https";
		}

		@Override
		public String serverName() {
			return "public.example";
		}

		@Override
		public int serverPort() {
			return 443;
		}
	}

	private static final class CapturedResponse implements LoopbackProxy.Response {

		private int status;
		private final Map<String, List<String>> headers = new TreeMap<>();
		private final ByteArrayOutputStream body = new ByteArrayOutputStream();

		@Override
		public void status(int value) {
			status = value;
		}

		@Override
		public void header(String name, String value) {
			headers.computeIfAbsent(name, ignored -> new java.util.ArrayList<>())
					.add(value);
		}

		@Override
		public ByteArrayOutputStream outputStream() throws IOException {
			return body;
		}
	}
}
