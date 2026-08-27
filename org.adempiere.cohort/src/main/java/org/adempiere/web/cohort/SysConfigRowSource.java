package org.adempiere.web.cohort;

import java.util.List;

/**
 * Reads every {@code AD_SysConfig} row named by
 * {@link CohortConfigurationKeys#all()} in one atomic operation.
 *
 * <p>The contract is deliberately all-or-nothing: an implementation must either
 * return the complete row set as of a single read, or throw. Returning a partial
 * set would let a transient failure look like "the allowlist is empty", which
 * routes people to the wrong runtime instead of failing closed.
 */
@FunctionalInterface
public interface SysConfigRowSource {

	/**
	 * @return every active and inactive row for the three reviewed names, at any
	 *         client and organisation scope
	 * @throws Exception when the rows could not be read as one consistent set
	 */
	List<SysConfigRow> read() throws Exception;
}
