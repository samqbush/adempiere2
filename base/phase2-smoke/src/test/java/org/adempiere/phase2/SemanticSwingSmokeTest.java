package org.adempiere.phase2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JTree;
import javax.swing.SwingUtilities;

import org.compiere.grid.tree.VTreePanel;
import org.compiere.process.ProcessInfo;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Login;
import org.eevolution.services.dsl.ProcessBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.spin.queue.process.FlushSystemQueue;
import org.spin.queue.process.FlushSystemQueueAbstract;

@Tag(Phase2SmokeTag.NAME)
class SemanticSwingSmokeTest {

	private static final String GARDEN_ADMIN = "GardenAdmin";
	private static final String GARDEN_WORLD_ADMIN = "GardenWorld Admin";

	@BeforeAll
	static void bootstrapRuntime() {
		Phase2RuntimeBootstrap.bootstrapServerRuntime();
	}

	@Test
	void authenticatesLoadsTheRealMenuAndInvokesAProcessSemantically() throws Exception {
		assertFalse(GraphicsEnvironment.isHeadless(),
			"Phase 2 semantic client smoke requires a real DISPLAY or xvfb-run and fails closed when Swing is headless.");

		Properties context = Env.getCtx();
		Login login = new Login(context);
		KeyNamePair role = find(
			login.getRoles(GARDEN_ADMIN, GARDEN_ADMIN), GARDEN_WORLD_ADMIN);
		KeyNamePair client = find(login.getClients(role), "GardenWorld");
		KeyNamePair org = first(login.getOrgs(client), "organization");
		KeyNamePair warehouse = first(login.getWarehouses(org), "warehouse");

		Env.setContext(context, "#AD_Role_ID", role.getKey());
		Env.setContext(context, "#AD_Client_ID", client.getKey());
		Env.setContext(context, "#AD_Org_ID", org.getKey());
		Env.setContext(context, "#M_Warehouse_ID", warehouse.getKey());
		String preferenceError = login.loadPreferences(
			org, warehouse, new Timestamp(System.currentTimeMillis()), "Adempiere");
		assertTrue(preferenceError == null || preferenceError.isBlank(),
			() -> "Garden World preference load failed: " + preferenceError);

		int treeId = DB.getSQLValueEx(null,
			"SELECT COALESCE(r.AD_Tree_Menu_ID, ci.AD_Tree_Menu_ID) "
				+ "FROM AD_ClientInfo ci INNER JOIN AD_Role r "
				+ "ON (ci.AD_Client_ID=r.AD_Client_ID) WHERE AD_Role_ID=?",
			role.getKey());
		assertTrue(treeId > 0, "Garden World role did not resolve a menu tree");

		AtomicReference<VTreePanel> panelReference = new AtomicReference<>();
		AtomicReference<JTree> treeReference = new AtomicReference<>();
		onEdt(() -> {
			VTreePanel panel = new VTreePanel(0, true, false);
			assertTrue(panel.initTree(treeId), "Garden World menu tree failed to initialize");
			JTree tree = findComponent(panel, JTree.class);
			assertNotNull(tree, "Real client menu did not expose a semantic JTree component");
			assertTrue(tree.getModel().getChildCount(tree.getModel().getRoot()) > 0,
				"Garden World menu tree loaded without any accessible menu nodes");
			panelReference.set(panel);
			treeReference.set(tree);
		});

		assertNotNull(panelReference.get());
		assertNotNull(treeReference.get());

		ProcessInfo processInfo = ProcessBuilder.create(context)
			.process(FlushSystemQueue.getProcessId())
			.withClientId(client.getKey())
			.withUserId(Env.getAD_User_ID(context))
			.withTitle("Phase 2 semantic client smoke")
			.withParameter(FlushSystemQueueAbstract.BATCHSTOPROCESS, 1)
			.withParameter(FlushSystemQueueAbstract.RECORDSBYBATCH, 1)
			.withParameter(FlushSystemQueueAbstract.ISDELETEAFTERPROCESS, false)
			.execute();
		assertFalse(processInfo.isError(),
			() -> "Semantic client process failed: " + processInfo.getSummary());
	}

	private static KeyNamePair find(KeyNamePair[] values, String name) {
		assertNotNull(values, () -> "No values returned while selecting " + name);
		return Arrays.stream(values)
			.filter(value -> name.equals(value.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(
				"Could not select " + name + " from " + Arrays.toString(values)));
	}

	private static KeyNamePair first(KeyNamePair[] values, String kind) {
		assertNotNull(values, () -> "No " + kind + " values returned");
		assertTrue(values.length > 0, () -> "No " + kind + " values available");
		return values[0];
	}

	private static <T extends Component> T findComponent(Container root, Class<T> type) {
		for (Component component : root.getComponents()) {
			if (type.isInstance(component)) {
				return type.cast(component);
			}
			if (component instanceof Container container) {
				T match = findComponent(container, type);
				if (match != null) {
					return match;
				}
			}
		}
		return null;
	}

	private static void onEdt(EdtAction action)
		throws InterruptedException, InvocationTargetException {

		SwingUtilities.invokeAndWait(() -> {
			try {
				action.run();
			}
			catch (Throwable throwable) {
				throw new AssertionError(throwable);
			}
		});
	}

	@FunctionalInterface
	private interface EdtAction {

		void run() throws Exception;
	}
}
