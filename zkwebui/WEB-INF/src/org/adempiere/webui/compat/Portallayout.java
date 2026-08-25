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

import org.zkoss.zk.ui.Component;
import org.zkoss.zul.Div;

/**
 * ADempiere-owned CE replacement for the commercial
 * {@code org.adempiere.webui.compat.Portallayout}.
 *
 * <p>The dashboard columns are rendered as plain block containers. That is
 * enough for the Phase 5d compile closure and preserves the component tree the
 * dashboard code builds. Drag and drop portal parity is Phase 5g work and is
 * intentionally not emulated here.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class Portallayout extends Div
{
	private static final long serialVersionUID = 5163131276889744616L;

	/** Style class applied so the Phase 5g theme work can target the layout. */
	public static final String SCLASS = "adempiere-portallayout";

	public Portallayout()
	{
		setSclass(SCLASS);
	}

	@Override
	public boolean isChildable()
	{
		return true;
	}

	@Override
	public void beforeChildAdded(Component child, Component refChild)
	{
		if (!(child instanceof Portalchildren))
			throw new UnsupportedOperationException(
					"Only Portalchildren is allowed in a Portallayout, got " + child);
		super.beforeChildAdded(child, refChild);
	}
}
