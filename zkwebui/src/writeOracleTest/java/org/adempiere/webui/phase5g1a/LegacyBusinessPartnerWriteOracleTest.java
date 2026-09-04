package org.adempiere.webui.phase5g1a;

import java.io.IOException;

import org.adempiere.webui.phase5g.BusinessPartnerWriteFlow;
import org.adempiere.webui.phase5g.WriteCaptureConfig;
import org.adempiere.webui.phase5g.Zk36Dialect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Captures the LEGACY Business Partner write flow through the public
 * {@code /webui} origin of the installed Tomcat 9 / ZK 3.6 product.
 *
 * <h2>What this class is, and is not</h2>
 *
 * <p>It is the capture half of the Phase 5g-1a oracle. It drives create,
 * update, duplicate submission and deactivate on {@code C_BPartner} and pauses
 * after each save so the orchestrator can take a database snapshot. It ships no
 * modern code and scores no parity.
 *
 * <p>Since Phase 5g-1b it is a thin binding rather than the driver itself: the
 * flow lives in {@link BusinessPartnerWriteFlow} and the ZK 3.6 mechanics in
 * {@link Zk36Dialect}, so the modern lane executes the <em>same</em> step
 * order, fact keys and session choreography under a different dialect. Nothing
 * about the legacy capture changed in that extraction, which the legacy
 * freeze-off regression re-proves on every Phase 5g-1b commit.
 *
 * <p>It deliberately does NOT know what the expected effect is. It never reads
 * {@code effect-model.tsv} and never asserts a business value. The moment a
 * driver asserts the answer, the answer becomes whatever the driver was written
 * to expect, and the oracle is scoring itself.
 */
@Tag("IntegrationTest")
class LegacyBusinessPartnerWriteOracleTest {

	@Test
	void capturesTheLegacyBusinessPartnerWriteFlow() throws IOException {
		new BusinessPartnerWriteFlow(
				new Zk36Dialect(),
				WriteCaptureConfig.fromProperties("phase5g1a.browser."))
				.capture();
	}
}
