package org.adempiere.web.bridge;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.web.cohort.CohortConfigurationRepository;
import org.adempiere.web.cohort.JdbcSysConfigRowSource;
import org.adempiere.web.handoff.HandoffKey;
import org.adempiere.web.handoff.HandoffKeyException;
import org.adempiere.web.handoff.HandoffTicketCodec;
import org.compiere.util.CLogger;
import org.compiere.util.DB;

/**
 * The one place the Tomcat 9 bridge resolves its configuration, its key and its
 * collaborators.
 *
 * <p>It is initialised exactly once, by {@link CohortBridgeStartupListener},
 * before any request is served. Every other bridge class reads it and never
 * constructs its own, so there is a single answer to "is Phase 5e active in this
 * context, and with which key".
 *
 * <h2>Failure policy</h2>
 *
 * <p>An absent handoff key means Phase 5e is not provisioned: the bridge stays
 * loaded, the interceptor never selects modern, and the context behaves exactly
 * as the frozen ZK 3.6 product does. An <em>invalid</em> key is different - it
 * means Phase 5e was provisioned and got it wrong - so the bridge records the
 * failure, keeps every new decision legacy, and reports one rate-limited
 * operator error rather than failing the whole legacy context. Tomcat 9 is the
 * only public ingress; taking it down because the modern lane is misconfigured
 * would turn a routing defect into an outage.
 */
public final class CohortBridge {

	/** System property, then environment variable, naming the shared key file. */
	public static final String KEY_PROPERTY = "adempiere.phase5e.handoffKey";

	/** Environment variable equivalent of {@link #KEY_PROPERTY}. */
	public static final String KEY_ENVIRONMENT = "ADEMPIERE_PHASE5E_HANDOFF_KEY";

	/** System property, then environment variable, naming the modern backend. */
	public static final String BACKEND_PROPERTY = "adempiere.phase5e.modernBackend";

	/** Environment variable equivalent of {@link #BACKEND_PROPERTY}. */
	public static final String BACKEND_ENVIRONMENT = "ADEMPIERE_PHASE5E_MODERN_BACKEND";

	/** Private-lane override; packaged runtimes retain the repository default. */
	public static final String CONFIGURATION_TTL_PROPERTY =
			"adempiere.phase5e.configurationTtlMillis";

	/** Servlet context attribute the backstop increments when it fires. */
	public static final String BACKSTOP_ATTRIBUTE =
			"org.adempiere.web.bridge.backstopTriggered";

	private static final CLogger log = CLogger.getCLogger(CohortBridge.class);

	private static volatile CohortBridge instance;

	private final CohortConfigurationRepository repository;
	private final HandoffTicketCodec codec;
	private final HandoffKey key;
	private final String backend;
	private final String keyFailure;

	private CohortBridge(
			CohortConfigurationRepository repository,
			HandoffTicketCodec codec,
			HandoffKey key,
			String backend,
			String keyFailure) {
		this.repository = repository;
		this.codec = codec;
		this.key = key;
		this.backend = backend;
		this.keyFailure = keyFailure;
	}

	/** Builds the bridge from the process environment. Called once, at startup. */
	static CohortBridge initialise() {
		long configurationTtlMillis = Long.getLong(
				CONFIGURATION_TTL_PROPERTY,
				CohortConfigurationRepository.DEFAULT_TTL_MILLIS);
		CohortConfigurationRepository repository = new CohortConfigurationRepository(
				new JdbcSysConfigRowSource(DB::getConnectionRO),
				(message, cause) -> log.log(Level.SEVERE, message, cause),
				System::currentTimeMillis,
				configurationTtlMillis,
				CohortConfigurationRepository.DEFAULT_ERROR_INTERVAL_MILLIS);
		String keyPath = setting(KEY_PROPERTY, KEY_ENVIRONMENT);
		String backend = setting(BACKEND_PROPERTY, BACKEND_ENVIRONMENT);
		HandoffKey key = null;
		String failure = null;
		if (keyPath == null || keyPath.isBlank()) {
			failure = "no handoff key is configured";
		} else {
			try {
				key = HandoffKey.load(Paths.get(keyPath));
			} catch (HandoffKeyException rejected) {
				failure = rejected.getMessage();
				log.log(Level.SEVERE,
						"Phase 5e cohort routing is disabled: " + rejected.getMessage(),
						rejected);
			}
		}
		if (key != null && (backend == null || backend.isBlank())) {
			failure = "no modern backend is configured";
			key = null;
			log.severe("Phase 5e cohort routing is disabled: " + failure);
		}
		instance = new CohortBridge(
				repository, new HandoffTicketCodec(), key,
				normaliseBackend(backend), failure);
		return instance;
	}

	static void shutdown() {
		instance = null;
	}

	/** The initialised bridge, or {@code null} before startup / after shutdown. */
	public static CohortBridge current() {
		return instance;
	}

	/** Test seam. Installs a fully constructed bridge. */
	public static void install(
			CohortConfigurationRepository repository,
			HandoffTicketCodec codec,
			HandoffKey key,
			String backend) {
		instance = new CohortBridge(repository, codec, key,
				normaliseBackend(backend), key == null ? "no handoff key" : null);
	}

	/**
	 * Whether the bridge may select the modern runtime at all.
	 *
	 * <p>False when the key is absent or invalid, which is what makes an
	 * unprovisioned or misconfigured deployment behave exactly like the frozen
	 * legacy product.
	 */
	public boolean routingPossible() {
		return key != null && backend != null;
	}

	/** The operator-facing reason routing is impossible, or {@code null}. */
	public String keyFailure() {
		return keyFailure;
	}

	public CohortConfigurationRepository repository() {
		return repository;
	}

	public HandoffTicketCodec codec() {
		return codec;
	}

	public HandoffKey key() {
		return key;
	}

	/** The modern backend origin, e.g. {@code http://127.0.0.1:8443}. */
	public String backend() {
		return backend;
	}

	/** ADempiere's own read-only pool; never a second connection configuration. */
	static Properties emptyProperties() {
		return new Properties();
	}

	private static String normaliseBackend(String backend) {
		if (backend == null || backend.isBlank()) {
			return null;
		}
		String trimmed = backend.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static String setting(String property, String environment) {
		String value = System.getProperty(property);
		if (value != null && !value.isBlank()) {
			return value;
		}
		return System.getenv(environment);
	}

	/** The loopback host the modern backend must be on. */
	public static Path keyPath() {
		String configured = setting(KEY_PROPERTY, KEY_ENVIRONMENT);
		return configured == null || configured.isBlank()
				? null
				: Paths.get(configured);
	}
}
