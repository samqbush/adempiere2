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

import org.zkoss.zul.Textbox;

/**
 * ADempiere-owned CE replacement for the {@code org.adempiere.webui.compat.HtmlEditor}
 * ZK 3.x add-on.
 *
 * <p>The add-on is a ZK 3.x only component and is not carried into the modern
 * runtime. ZK CE 10 has no bundled rich text editor, so the HTML tab of the
 * text editor dialog degrades to an HTML source editor built on the supported
 * CE {@link Textbox}. It keeps the {@code value} and {@code onChange} contract
 * the dialog relies on. Restoring a rich text editing experience is Phase 5g
 * work.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class HtmlEditor extends Textbox
{
	private static final long serialVersionUID = -1331570303570747329L;

	/** Style class the Phase 5g theme work can target. */
	public static final String SCLASS = "adempiere-html-editor";

	public HtmlEditor()
	{
		setMultiline(true);
		setSclass(SCLASS);
	}
}
