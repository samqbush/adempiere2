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

import java.util.Comparator;

import org.zkoss.zul.ext.Sortable;

/**
 * ADempiere-owned replacement for the ZK 3.6 {@code org.zkoss.zul.ListModelExt}
 * interface.
 *
 * <p>ZK CE 10 expresses the same capability through
 * {@code org.zkoss.zul.ext.Sortable}, which the CE list models already
 * implement. This interface keeps the ZK 3.6 contract that the ADempiere list
 * models and info panels declare, so sorting stays an explicit part of their
 * public API.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public interface ListModelExt extends Sortable
{
	/**
	 * @param comparator comparator to sort with
	 * @param ascending true to sort ascending
	 */
	@SuppressWarnings("rawtypes")
	public void sort(Comparator comparator, boolean ascending);

	@Override
	@SuppressWarnings("rawtypes")
	public default String getSortDirection(Comparator comparator)
	{
		return "natural";
	}
}
