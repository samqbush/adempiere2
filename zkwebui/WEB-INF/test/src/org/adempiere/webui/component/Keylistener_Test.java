package org.adempiere.webui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 5d regression proofs for the ZK CE control-key compatibility layer.
 *
 * <p>ADempiere's shortcut specification came from the ZK 3.x
 * {@code org.zkforge.keylistener} add-on and contains {@code #enter}. ZK CE's
 * own parser rejects the <em>entire</em> specification on the first unknown
 * extended key ({@code zul/Widget.ts setCtrlKeys}), so passing the legacy string
 * through unchanged silently disabled every ADempiere keyboard shortcut and
 * raised {@code setCtrlKeys: Unknown #enter} in the browser. These tests pin the
 * two halves of the fix: unsupported extended keys never reach ZK, and every
 * supported one still does.
 */
class Keylistener_Test {

	/** The specification AdempiereWebUI.onChangeRole installs verbatim. */
	private static final String LEGACY_SPECIFICATION =
			"@a@c@d@e@f@h@l@m@n@o@p@r@s@t@z@x@#left@#right@#up@#down@#home@#end"
					+ "#enter^u@u@#pgdn@#pgup$#f2^#f2";

	@Test
	void theLegacyEnterKeyNeverReachesZk() {
		String translated = Keylistener.toZkCtrlKeys(LEGACY_SPECIFICATION);
		assertFalse(translated.contains("#enter"),
				"ZK CE would reject the whole specification: " + translated);
		assertFalse(translated.toLowerCase().contains("enter"),
				"An 'enter' fragment survived translation: " + translated);
	}

	@Test
	void everySupportedExtendedKeySurvives() {
		String translated = Keylistener.toZkCtrlKeys(LEGACY_SPECIFICATION);
		for (String supported : new String[] {
				"#left", "#right", "#up", "#down", "#home", "#end",
				"#pgdn", "#pgup", "#f2" }) {
			assertTrue(translated.contains(supported),
					supported + " was lost from " + translated);
		}
		for (char letter : "acdefhlmnoprstzxu".toCharArray()) {
			assertTrue(translated.contains("@" + letter) || translated.contains("^" + letter),
					"Alt/Ctrl+" + letter + " was lost from " + translated);
		}
	}

	@Test
	void aDroppedExtendedKeyTakesItsModifiersWithIt() {
		// '@#enter' would otherwise leave a dangling '@', which ZK reports as an
		// unexpected key combination and rejects just as loudly.
		assertEquals("@a", Keylistener.toZkCtrlKeys("@a@#enter"));
		assertEquals("@a", Keylistener.toZkCtrlKeys("@a^$@#enter"));
		assertEquals("", Keylistener.toZkCtrlKeys("#enter"));
	}

	@Test
	void unknownExtendedKeysAreRejectedAndKnownOnesAreNot() {
		assertEquals("", Keylistener.toZkCtrlKeys("#escape"));
		assertEquals("", Keylistener.toZkCtrlKeys("#f13"));
		assertEquals("#f12", Keylistener.toZkCtrlKeys("#f12"));
		assertEquals("#f1", Keylistener.toZkCtrlKeys("#f1"));
		assertEquals("#bak#tab#space#ins#del",
				Keylistener.toZkCtrlKeys("#bak#tab#space#ins#del"));
	}

	@Test
	void nullAndEmptySpecificationsArePassedThrough() {
		assertNull(Keylistener.toZkCtrlKeys(null));
		assertEquals("", Keylistener.toZkCtrlKeys(""));
	}

	@Test
	void theCallerStillSeesTheSpecificationItSet() {
		Keylistener listener = new Keylistener();
		listener.setCtrlKeys(LEGACY_SPECIFICATION);
		assertEquals(LEGACY_SPECIFICATION, listener.getLegacyCtrlKeys(),
				"The compatibility layer lost the caller's own value");
		assertEquals(Keylistener.toZkCtrlKeys(LEGACY_SPECIFICATION),
				listener.getZkCtrlKeys(),
				"The specification handed to ZK is not the translated one");
		assertTrue(listener.isForwardingEnter(),
				"Enter must be re-implemented on ZK CE's onOK event, not dropped");
		assertEquals(13, Keylistener.ENTER_KEY_CODE,
				"Messagebox and InfoPanel test for key code 13");
	}

	@Test
	void zkNeverSeesTheLegacySpecificationThroughTheGetter() {
		// ZK CE renders a component's own control keys by calling getCtrlKeys()
		// (XulElement.renderProperties). Returning the legacy value there pushes
		// #enter to the client without any caller ever asking for it, which is
		// exactly how the modern desktop first failed: the raw
		// "#f5#del^d^s...#enter" specification from GridPanel reached the browser
		// through the getter and ZK rejected the whole thing.
		Keylistener listener = new Keylistener();
		listener.setCtrlKeys(GridPanelSpecification.MOVE);
		assertFalse(listener.getCtrlKeys().contains("#enter"),
				"getCtrlKeys() would render #enter to the ZK CE client: "
						+ listener.getCtrlKeys());
		assertEquals(listener.getZkCtrlKeys(), listener.getCtrlKeys(),
				"getCtrlKeys() must report exactly what ZK CE is given");
		assertTrue(listener.getLegacyCtrlKeys().contains("#enter"),
				"The legacy specification was lost instead of translated");
	}

	/** The literal GridPanel specification, kept verbatim as a regression pin. */
	private static final class GridPanelSpecification {
		/** GridPanel.CNTRL_KEYS + GridPanel.KEYS_MOVE. */
		static final String MOVE =
				"#f5#del^d^s#pgup#pgdn#end#home#up#down#left#right#enter";
	}

	@Test
	void aSpecificationWithoutEnterDoesNotForwardOnOk() {
		Keylistener listener = new Keylistener();
		listener.setCtrlKeys("@a@#home");
		assertFalse(listener.isForwardingEnter(),
				"onOK was forwarded for a specification that never asked for Enter");
		assertEquals("@a@#home", listener.getZkCtrlKeys());
	}

	@Test
	void theLegacyOnlyKeyIsNamed() {
		assertTrue(Keylistener.legacyOnlyExtendedKeys().contains("enter"),
				"The compatibility layer no longer records which legacy-only "
						+ "extended keys it re-implements");
	}
}
