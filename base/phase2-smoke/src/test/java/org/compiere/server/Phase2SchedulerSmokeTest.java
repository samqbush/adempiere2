package org.compiere.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.adempiere.phase2.Phase2RuntimeBootstrap;
import org.adempiere.phase2.Phase2SmokeTag;
import org.compiere.model.MScheduler;
import org.compiere.model.MSchedulerLog;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.spin.queue.process.FlushSystemQueue;

@Tag(Phase2SmokeTag.NAME)
class Phase2SchedulerSmokeTest {

	@BeforeAll
	static void bootstrapRuntime() {
		Phase2RuntimeBootstrap.bootstrapServerRuntime();
	}

	@Test
	void runsOnceAndRestoresContextAndTransactions() {
		Properties originalContext = Env.getCtx();
		originalContext.setProperty("PHASE2_SMOKE_SENTINEL", "still-present");
		Set<String> transactionNamesBeforeRun = Phase2RuntimeBootstrap.snapshotTransactionNames();

		MScheduler schedulerModel = createDoesNotRepeatScheduler();
		try {
			InspectableScheduler scheduler = new InspectableScheduler(schedulerModel);
			assertTrue(scheduler.isEligibleToRun(),
				"Fresh DoesNotRepeat scheduler should be eligible before its first run");

			scheduler.runNow();

			MScheduler reloadedScheduler = new MScheduler(
				Env.getCtx(),
				schedulerModel.getAD_Scheduler_ID(),
				null);
			assertFalse(scheduler.isEligibleToRun(),
				"DoesNotRepeat scheduler should refuse a second semantic run after DateLastRun is set");
			assertNotNull(reloadedScheduler.getDateLastRun(),
				"Scheduler run did not persist DateLastRun");
			assertEquals(1, countLogs(reloadedScheduler.getAD_Scheduler_ID()),
				"Scheduler smoke run must emit exactly one AD_SchedulerLog record");

			assertSame(originalContext, Env.getCtx(),
				"Scheduler smoke run did not restore the original Env context reference");
			assertEquals("still-present", Env.getCtx().getProperty("PHASE2_SMOKE_SENTINEL"),
				"Scheduler smoke run did not preserve the caller Env context data");

			Set<String> transactionNamesAfterRun = Phase2RuntimeBootstrap.snapshotTransactionNames();
			transactionNamesAfterRun.removeAll(transactionNamesBeforeRun);
			assertTrue(transactionNamesAfterRun.stream().noneMatch(name -> name.startsWith("Scheduler_")),
				"Scheduler smoke run leaked transaction handles: " + transactionNamesAfterRun);
		}
		finally {
			originalContext.remove("PHASE2_SMOKE_SENTINEL");
			deleteScheduler(schedulerModel);
		}
	}

	private static MScheduler createDoesNotRepeatScheduler() {
		AtomicInteger schedulerId = new AtomicInteger();
		Trx.run(trxName -> {
			MScheduler scheduler = new MScheduler(Env.getCtx(), 0, trxName);
			scheduler.setAD_Org_ID(11);
			scheduler.setName("Phase2 Smoke Scheduler " + System.nanoTime());
			scheduler.setDescription("Phase 2 exact-once scheduler smoke");
			scheduler.setAD_Process_ID(FlushSystemQueue.getProcessId());
			scheduler.setSupervisor_ID(100);
			scheduler.setScheduleType(MScheduler.SCHEDULETYPE_Frequency);
			scheduler.setFrequencyType(MScheduler.FREQUENCYTYPE_DoesNotRepeat);
			scheduler.setFrequency(1);
			scheduler.setKeepLogDays(7);
			scheduler.setDateNextRun(new Timestamp(System.currentTimeMillis() - 1000L));
			scheduler.saveEx();
			schedulerId.set(scheduler.getAD_Scheduler_ID());
		});
		return new MScheduler(Env.getCtx(), schedulerId.get(), null);
	}

	private static int countLogs(int schedulerId) {
		return new Query(Env.getCtx(), MSchedulerLog.Table_Name, "AD_Scheduler_ID=?", null)
			.setParameters(schedulerId)
			.count();
	}

	private static void deleteScheduler(MScheduler scheduler) {
		if (scheduler == null || scheduler.getAD_Scheduler_ID() <= 0) {
			return;
		}
		Trx.run(trxName -> {
			new Query(Env.getCtx(), MSchedulerLog.Table_Name, "AD_Scheduler_ID=?", trxName)
				.setParameters(scheduler.getAD_Scheduler_ID())
				.list()
				.forEach(log -> log.deleteEx(true));

			MScheduler toDelete = new MScheduler(Env.getCtx(), scheduler.getAD_Scheduler_ID(), trxName);
			if (toDelete.getAD_Scheduler_ID() > 0) {
				toDelete.deleteEx(true);
			}
		});
	}

	private static final class InspectableScheduler extends Scheduler {

		private InspectableScheduler(MScheduler model) {
			super(model);
		}

		private boolean isEligibleToRun() {
			return super.isValidForRun();
		}
	}
}
