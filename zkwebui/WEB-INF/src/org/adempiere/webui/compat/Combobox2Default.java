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
package org.adempiere.webui.compat;

import org.zkoss.zul.Combobox;

/**
 * ADempiere-owned CE replacement for the ZK 3.6 combobox mold renderer that
 * previously lived in the commercial {@code org.zkoss.zkmax.zul.render}
 * namespace.
 *
 * <p>ZK 3.6 rendered components on the server through the
 * {@code org.zkoss.zk.ui.render.ComponentRenderer} SPI, which ZK removed when
 * rendering moved to the client widgets. The only ADempiere specific behaviour
 * in the old renderer was forcing the combobox inner input to fill the
 * component width, so that is what this type applies through the supported
 * ZK CE 10 API.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public final class Combobox2Default
{
	/** Style class the Phase 5g theme work can target. */
	public static final String SCLASS = "adempiere-combobox2";

	private Combobox2Default()
	{
	}

	/**
	 * Applies the ADempiere combobox default mold: the editable input fills the
	 * available width instead of using the ZK intrinsic input width.
	 *
	 * @param combobox combobox to adjust, may be null
	 */
	public static void apply(Combobox combobox)
	{
		if (combobox == null)
			return;
		combobox.setSclass(SCLASS);
		combobox.setInplace(false);
		combobox.setClientAttribute("data-adempiere-inputwidth", "100%");
	}
}
