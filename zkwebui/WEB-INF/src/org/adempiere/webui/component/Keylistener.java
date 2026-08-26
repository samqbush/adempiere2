/******************************************************************************
 * ADempiere ERP & CRM Smart Business Solution                                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.adempiere.webui.component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.KeyEvent;
import org.zkoss.zul.Div;
import org.zkoss.zul.impl.XulElement;

/**
 * Keyboard shortcut host built on the native ZK CE {@code ctrlKeys} support.
 *
 * <p>ADempiere previously used the {@code org.zkforge.keylistener} ZK 3.x
 * add-on, which is not carried into the modern runtime. ZK CE 10 implements the
 * same capability natively, but ZK dispatches shortcuts only through ancestors
 * of the focused component. Callers therefore bind this compatibility object
 * to the window or panel that owns the focusable content. Events are forwarded
 * with this component as their target so existing listeners keep their legacy
 * contract.</p>
 *
 * <h2>Enter</h2>
 *
 * The ZK 3.x add-on accepted {@code #enter} inside a control-key specification.
 * ZK CE's own parser does not: its extended-key vocabulary is
 * {@code pgup pgdn end home left up right down ins del bak tab space f1..f12},
 * and an unknown {@code #name} makes ZK reject the <em>entire</em> specification
 * on the client with {@code setCtrlKeys: Unknown #enter}. Passing the legacy
 * string through unchanged therefore did not merely lose Enter, it silently
 * disabled every ADempiere keyboard shortcut.
 *
 * <p>Unsupported extended keys are stripped before the specification reaches ZK,
 * and {@code #enter} is re-implemented on ZK CE's own {@code onOK} event, which
 * is how ZK CE reports Enter. Listeners still receive an {@code onCtrlKey}
 * {@link KeyEvent} carrying key code 13, which is what
 * {@code Messagebox} and {@code InfoPanel} test for.
 *
 * @author ADempiere Phase 5d modernization
 */
public class Keylistener extends Div
{
	private static final long serialVersionUID = 1074160204090748931L;

	/** Style class the Phase 5g theme work can target. */
	public static final String SCLASS = "adempiere-keylistener";

	/** The Enter key code the legacy listeners test for. */
	public static final int ENTER_KEY_CODE = 13;

	/**
	 * ZK CE's extended-key vocabulary, transcribed from
	 * {@code zul/Widget.ts setCtrlKeys}. Function keys {@code #f1} to
	 * {@code #f12} are accepted separately.
	 */
	private static final Set<String> ZK_EXTENDED_KEYS = new LinkedHashSet<>(
			Arrays.asList("pgup", "pgdn", "end", "home", "left", "up", "right",
					"down", "ins", "del", "bak", "tab", "space"));

	/** Extended keys the ZK 3.x add-on accepted and ZK CE re-implements elsewhere. */
	private static final List<String> LEGACY_ONLY_KEYS = List.of("enter");

	private static final CLogger log = CLogger.getCLogger(Keylistener.class);

	private boolean autoBlur = true;
	private String ctrlKeys;
	private String zkCtrlKeys;
	private boolean forwardEnter;
	private XulElement host;
	private final EventListener<Event> hostListener = event -> {
		KeyEvent keyEvent = (KeyEvent) event;
		Events.sendEvent(new KeyEvent(
				Events.ON_CTRL_KEY,
				this,
				keyEvent.getKeyCode(),
				keyEvent.isCtrlKey(),
				keyEvent.isShiftKey(),
				keyEvent.isAltKey()));
	};
	private final EventListener<Event> enterListener = event ->
			Events.sendEvent(new KeyEvent(
					Events.ON_CTRL_KEY, this, ENTER_KEY_CODE, false, false, false));

	public Keylistener()
	{
		setSclass(SCLASS);
		setStyle("display:none");
	}

	/**
	 * Binds shortcuts to an ancestor of the focusable content.
	 *
	 * @param component shortcut owner or one of its descendants
	 */
	public void bindTo(Component component)
	{
		XulElement host = null;
		for (Component candidate = component; candidate != null;
				candidate = candidate.getParent())
		{
			if (candidate instanceof XulElement)
			{
				host = (XulElement) candidate;
				break;
			}
		}
		if (this.host == host)
			return;
		if (this.host != null)
		{
			this.host.removeEventListener(Events.ON_CTRL_KEY, hostListener);
			this.host.removeEventListener(Events.ON_OK, enterListener);
			this.host.setCtrlKeys(null);
		}
		this.host = host;
		if (host != null)
		{
			host.addEventListener(Events.ON_CTRL_KEY, hostListener);
			applyToHost();
		}
	}

	@Override
	public void setCtrlKeys(String ctrlKeys)
	{
		this.ctrlKeys = ctrlKeys;
		this.zkCtrlKeys = toZkCtrlKeys(ctrlKeys);
		this.forwardEnter = ctrlKeys != null
				&& !ctrlKeys.equals(this.zkCtrlKeys)
				&& ctrlKeys.toLowerCase(Locale.ROOT).contains("#enter");
		if (host != null)
			applyToHost();
	}

	/**
	 * @return the specification ZK CE accepts, with legacy-only extended keys
	 *         removed
	 *
	 * <p>This deliberately returns the <em>translated</em> value, not the one the
	 *   caller supplied. ZK CE renders a component's own control keys by calling
	 *   this getter ({@code XulElement.renderProperties}), so returning the legacy
	 *   specification would push {@code #enter} to the client through the back
	 *   door and reproduce the very failure the translation exists to prevent -
	 *   even though no caller ever passed it to {@code setCtrlKeys}. The
	 *   caller's own value remains available through
	 *   {@link #getLegacyCtrlKeys()}.
	 */
	@Override
	public String getCtrlKeys()
	{
		return zkCtrlKeys;
	}

	/**
	 * @return the specification exactly as the caller supplied it, including any
	 *         legacy-only extended key
	 */
	public String getLegacyCtrlKeys()
	{
		return ctrlKeys;
	}

	/**
	 * @return the specification actually handed to ZK CE, with legacy-only
	 *         extended keys removed
	 */
	public String getZkCtrlKeys()
	{
		return zkCtrlKeys;
	}

	/**
	 * @return whether Enter is re-implemented through ZK CE's {@code onOK}
	 */
	public boolean isForwardingEnter()
	{
		return forwardEnter;
	}

	private void applyToHost()
	{
		host.setCtrlKeys(zkCtrlKeys);
		host.removeEventListener(Events.ON_OK, enterListener);
		if (forwardEnter)
			host.addEventListener(Events.ON_OK, enterListener);
	}

	/**
	 * Removes extended keys ZK CE's parser does not know.
	 *
	 * <p>ZK rejects the whole specification on the first unknown {@code #name},
	 * so this cannot be left to the client.
	 *
	 * @param specification legacy control-key specification, may be {@code null}
	 * @return a specification ZK CE accepts, or {@code null}
	 */
	static String toZkCtrlKeys(String specification)
	{
		if (specification == null || specification.isEmpty())
			return specification;
		StringBuilder accepted = new StringBuilder(specification.length());
		StringBuilder dropped = new StringBuilder();
		for (int index = 0; index < specification.length(); index++)
		{
			char current = specification.charAt(index);
			if (current != '#')
			{
				accepted.append(current);
				continue;
			}
			int end = index + 1;
			while (end < specification.length()
					&& Character.isLetterOrDigit(specification.charAt(end)))
				end++;
			String name = specification.substring(index + 1, end)
					.toLowerCase(Locale.ROOT);
			if (isZkExtendedKey(name))
				accepted.append(specification, index, end);
			else
			{
				dropped.append(dropped.length() == 0 ? "" : ", ").append('#').append(name);
				// A modifier that was only there to qualify the dropped key must
				// go with it, otherwise ZK reports an unexpected combination.
				int last = accepted.length() - 1;
				while (last >= 0 && isModifier(accepted.charAt(last)))
				{
					accepted.deleteCharAt(last);
					last--;
				}
			}
			index = end - 1;
		}
		if (dropped.length() > 0 && log.isLoggable(Level.CONFIG))
			log.config("ZK CE does not implement " + dropped
					+ "; removed from the control-key specification");
		return accepted.toString();
	}

	private static boolean isModifier(char character)
	{
		return character == '^' || character == '@' || character == '$'
				|| character == '%';
	}

	private static boolean isZkExtendedKey(String name)
	{
		if (ZK_EXTENDED_KEYS.contains(name))
			return true;
		if (name.length() > 1 && name.charAt(0) == 'f')
		{
			try
			{
				int number = Integer.parseInt(name.substring(1));
				return number >= 1 && number <= 12;
			}
			catch (NumberFormatException exception)
			{
				return false;
			}
		}
		return false;
	}

	/**
	 * @return the legacy-only extended keys this compatibility layer
	 *         re-implements rather than forwards
	 */
	public static List<String> legacyOnlyExtendedKeys()
	{
		return LEGACY_ONLY_KEYS;
	}

	/**
	 * The ZK 3.x add-on could blur the focused editor before dispatching a
	 * shortcut. ZK CE 10 always dispatches {@code onCtrlKey} without blurring,
	 * so this flag is kept for the legacy call sites and reported back
	 * unchanged.
	 *
	 * @param autoBlur legacy auto blur flag
	 */
	public void setAutoBlur(boolean autoBlur)
	{
		this.autoBlur = autoBlur;
	}

	/**
	 * @return legacy auto blur flag
	 */
	public boolean isAutoBlur()
	{
		return autoBlur;
	}
}
