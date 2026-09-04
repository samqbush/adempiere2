package org.adempiere.webui.phase5g1b;

import java.io.IOException;

import org.adempiere.webui.phase5g.BusinessPartnerWriteFlow;
import org.adempiere.webui.phase5g.WriteCaptureConfig;
import org.adempiere.webui.phase5g.ZkCe10Dialect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Captures the MODERN Business Partner write flow through the public routed
 * {@code /webui} origin: ZK CE {@code 10.3.0.1-jakarta} on Tomcat 10.1, reached
 * through the Tomcat 9 ingress, the cohort decision, the session handoff and
 * the proxy.
 *
 * <h2>What this class is, and is not</h2>
 *
 * <p>It is a capture, not a comparison. It executes the same
 * {@link BusinessPartnerWriteFlow} the legacy oracle executed -- the same step
 * order and ids, the same session choreography, the same emitted fact keys in
 * the same order -- under {@link ZkCe10Dialect}. It asserts no business value
 * and knows no expected effect.
 *
 * <p>The verdict is reached elsewhere, by
 * {@code scripts/phase5/score-write-oracle-capture.py} in freeze-off mode,
 * against the frozen {@code contracts/legacy-web-write-v1/}. That separation is
 * the whole parity claim: if this class could read the expected answer, it
 * could be written to produce it, and a green lane would prove only that
 * somebody had transcribed the contract into a driver.
 *
 * <h2>Why it runs against the public origin</h2>
 *
 * <p>ADR decision 6 forbids scoring on the direct loopback {@code /webui-modern}
 * origin. What is under test is the modern runtime as a user reaches it. A
 * capture taken with the whole routing layer bypassed would answer a question
 * nobody asked.
 *
 * <h2>Why a failure here is usually a real finding</h2>
 *
 * <p>Phase 5d proved login, role selection, menu and a read-only window on this
 * runtime. It proved no write. So the first several runs of this capture are
 * expected to surface modern write-path defects -- the editors, the save path,
 * the concurrency popup, and the settlement change from ZK 3.6's Comet
 * transport to ZK CE 10's polling. Each is a defect to fix in
 * {@code WEB-INF/src}, never a difference to reclassify: there is deliberately
 * no divergence list for one to be reclassified into.
 */
@Tag("IntegrationTest")
class ModernBusinessPartnerWriteParityTest {

	@Test
	void capturesTheModernBusinessPartnerWriteFlow() throws IOException {
		new BusinessPartnerWriteFlow(
				new ZkCe10Dialect(),
				WriteCaptureConfig.fromProperties("phase5g1a.browser."))
				.capture();
	}
}
