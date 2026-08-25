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

import org.zkoss.zul.Div;

/**
 * ADempiere-owned CE replacement for the commercial
 * {@code org.adempiere.webui.compat.Portalchildren}, the column container of a
 * {@link Portallayout}.
 *
 * @author ADempiere Phase 5d modernization
 */
public class Portalchildren extends Div
{
	private static final long serialVersionUID = -3474420585113941049L;

	/** Style class applied so the Phase 5g theme work can target the column. */
	public static final String SCLASS = "adempiere-portalchildren";

	public Portalchildren()
	{
		setSclass(SCLASS);
	}
}
