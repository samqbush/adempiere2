package org.adempiere.demo;

import java.time.Instant;
import java.util.Properties;

import org.compiere.Adempiere;
import org.compiere.model.MBPartner;
import org.compiere.util.DB;
import org.compiere.util.Env;

public final class FirstModernDemoVerifier {
	private static final int BUSINESS_PARTNER_TABLE_ID = 291;
	private static final int CLIENT_ID = 11;
	private static final int ORGANIZATION_ID = 50001;
	private static final int USER_ID = 101;
	private static final int ROLE_ID = 102;

	private FirstModernDemoVerifier() {
	}

	public static void main(String[] args) throws Exception {
		Adempiere.startup(true);
		Properties context = Env.getCtx();
		Env.setContext(context, "#AD_Client_ID", CLIENT_ID);
		Env.setContext(context, "#AD_Org_ID", ORGANIZATION_ID);
		Env.setContext(context, "#AD_User_ID", USER_ID);
		Env.setContext(context, "#AD_Role_ID", ROLE_ID);
		Env.setContext(context, "#SalesRep_ID", USER_ID);
		Env.setContext(context, "#AD_Language", "en_US");

		String suffix = Long.toUnsignedString(Instant.now().toEpochMilli(), 36)
			.toUpperCase();
		String value = "DEMO-" + suffix;
		String name = value + " Modern Business Partner";

		MBPartner partner = new MBPartner(context, 0, null);
		partner.setAD_Org_ID(ORGANIZATION_ID);
		partner.setValue(value);
		partner.setName(name);
		partner.setC_BP_Group_ID(103);
		partner.setSalesRep_ID(USER_ID);
		partner.setM_PriceList_ID(101);
		partner.setC_PaymentTerm_ID(105);
		partner.setC_Dunning_ID(100);
		partner.setIsCustomer(false);
		partner.setIsVendor(false);
		partner.setIsEmployee(false);
		partner.setIsSalesRep(false);
		partner.saveEx();

		int partnerId = partner.getC_BPartner_ID();
		String persistedName = DB.getSQLValueStringEx(null,
			"SELECT Name FROM C_BPartner WHERE C_BPartner_ID=?", partnerId);
		if (!name.equals(persistedName)) {
			throw new IllegalStateException("Business Partner read-back did not match");
		}

		int processId = waitForWorkflow(partnerId);
		assertCount("workflow activity", 1,
			"SELECT count(*) FROM AD_WF_Activity "
				+ "WHERE AD_WF_Process_ID=? AND AD_Table_ID=? AND Record_ID=?",
			processId, BUSINESS_PARTNER_TABLE_ID, partnerId);
		assertCount("workflow event audit", 1,
			"SELECT count(*) FROM AD_WF_EventAudit "
				+ "WHERE AD_WF_Process_ID=? AND AD_Table_ID=? AND Record_ID=?",
			processId, BUSINESS_PARTNER_TABLE_ID, partnerId);
		assertCount("saving-context workflow attribution", 1,
			"SELECT count(*) FROM AD_WF_Process WHERE AD_WF_Process_ID=? "
				+ "AND AD_Client_ID=? AND AD_User_ID=?",
			processId, CLIENT_ID, USER_ID);

		System.out.printf(
			"verified business_partner_id=%d value=%s workflow_process_id=%d "
				+ "client_id=%d user_id=%d%n",
			partnerId, value, processId, CLIENT_ID, USER_ID);
	}

	private static int waitForWorkflow(int partnerId) throws Exception {
		long deadline = System.currentTimeMillis() + 30000;
		do {
			int processId = DB.getSQLValueEx(null,
				"SELECT max(AD_WF_Process_ID) FROM AD_WF_Process "
					+ "WHERE AD_Table_ID=? AND Record_ID=?",
				BUSINESS_PARTNER_TABLE_ID, partnerId);
			if (processId > 0) {
				return processId;
			}
			Thread.sleep(500);
		} while (System.currentTimeMillis() < deadline);
		throw new IllegalStateException(
			"Business Partner workflow process was not created");
	}

	private static void assertCount(String label, int minimum, String sql,
			Object... parameters) {
		int count = DB.getSQLValueEx(null, sql, parameters);
		if (count < minimum) {
			throw new IllegalStateException(
				label + " expected at least " + minimum + " row(s), found " + count);
		}
	}
}
