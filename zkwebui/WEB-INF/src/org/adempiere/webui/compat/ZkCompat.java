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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.HtmlBasedComponent;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.LayoutRegion;
import org.zkoss.zul.Panel;
import org.zkoss.zul.Row;
import org.zkoss.zul.impl.MeshElement;

/**
 * ADempiere-owned compatibility helpers for ZK component properties that
 * existed in ZK 3.6 and were removed from ZK CE 10.
 *
 * <p>Every helper is deliberately narrow: it either applies the closest ZK CE
 * equivalent or records the legacy intent without touching unrelated state.
 * Broad visual parity for the migrated screens is owned by Phase 5g; this class
 * only has to keep the Phase 5d compile closure honest and behaviour-safe.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public final class ZkCompat
{
	private static final Logger log = Logger.getLogger(ZkCompat.class.getName());

	private ZkCompat()
	{
	}

	/** Client attribute recording a height cleared to satisfy a ZK CE vflex. */
	public static final String LEGACY_HEIGHT_ATTRIBUTE =
			"data-adempiere-legacy-height";

	/**
	 * Sets {@code vflex} on a component that may already carry an explicit
	 * height.
	 *
	 * <p>ZK CE 10 enforces the "flex or size, not both" rule in both directions:
	 * {@code setVflex} throws when a height is set, and {@code setHeight} throws
	 * when a vflex is set (ZK CE 10 {@code HtmlBasedComponent}). ZK 3.6 accepted
	 * both and let the flex win. Screens migrated from ZK 3.6 therefore contain
	 * pairs such as {@code grid.setHeight("100%"); grid.setVflex(true);}, which
	 * abort the whole ZK event on ZK CE.
	 *
	 * <p>This helper reproduces the ZK 3.6 outcome - flex wins - by clearing the
	 * explicit height first and recording what the screen asked for.
	 *
	 * @param component target component, may be null
	 * @param flex legacy vflex flag
	 */
	public static void setVflex(HtmlBasedComponent component, boolean flex)
	{
		if (component == null)
			return;
		if (flex)
		{
			String height = component.getHeight();
			if (height != null)
			{
				component.setClientAttribute(LEGACY_HEIGHT_ATTRIBUTE, height);
				component.setHeight(null);
			}
			component.setVflex("true");
		}
		else
		{
			component.setVflex("false");
		}
	}

	/**
	 * Sets an explicit height on a component that may already carry a vflex.
	 *
	 * <p>The mirror of {@link #setVflex(HtmlBasedComponent, boolean)} for the
	 * migrated screens that set the height <em>after</em> the flex. ZK 3.6 let
	 * the later call win; ZK CE throws. The vflex is cleared so the explicit
	 * height applies, which is the ZK 3.6 outcome.
	 *
	 * @param component target component, may be null
	 * @param height explicit height
	 */
	public static void setHeight(HtmlBasedComponent component, String height)
	{
		if (component == null)
			return;
		if (height != null && component.getVflex() != null)
			component.setVflex(null);
		component.setHeight(height);
	}

	/**
	 * ZK 3.6 {@code Borderlayout} regions carried a {@code flex} flag that made
	 * the single child fill the region regardless of any width or height the
	 * child declared. ZK CE 10 regions always size their content, so the flag
	 * has no direct counterpart. The legacy value is propagated to the region
	 * children that already exist, which is the documented ZK 5+ replacement
	 * (child {@code hflex}/{@code vflex}).
	 *
	 * <p>A child that already declares an explicit size is left alone in that
	 * dimension. Two reasons, and the second one is the important one:
	 *
	 * <ol>
	 *   <li>ZK CE rejects {@code hflex} together with {@code width} and
	 *       {@code vflex} together with {@code height}
	 *       ({@code HtmlBasedComponent}), so setting both aborts the request;</li>
	 *   <li>a child that declares {@code width="100%"; height="100%"} inside a
	 *       region already fills it, so replacing that with a flex value changes
	 *       nothing for the better and, for an absolutely positioned child such
	 *       as {@code HeaderPanel}'s user panel, collapses it and lets the
	 *       region body cover the controls it contains.</li>
	 * </ol>
	 *
	 * @param region border layout region, may be null
	 * @param flex legacy flex flag
	 */
	public static void setFlex(LayoutRegion region, boolean flex)
	{
		if (region == null)
			return;
		for (Component child : region.getChildren())
		{
			if (!(child instanceof HtmlBasedComponent))
				continue;
			HtmlBasedComponent html = (HtmlBasedComponent) child;
			if (flex)
			{
				if (html.getWidth() == null)
					html.setHflex("1");
				if (html.getHeight() == null)
					html.setVflex("1");
			}
			else
			{
				if (html.getHflex() != null)
					html.setHflex(null);
				if (html.getVflex() != null)
					html.setVflex(null);
			}
		}
	}

	/**
	 * ZK 3.6 rendered an {@code align} attribute for {@code Div}, {@code Panel}
	 * and {@code Image}. ZK CE 10 dropped the property but still forwards
	 * client attributes verbatim, so the rendered DOM stays identical.
	 *
	 * @param component target component, may be null
	 * @param align legacy horizontal alignment
	 */
	public static void setAlign(HtmlBasedComponent component, String align)
	{
		if (component == null)
			return;
		component.setClientAttribute("align", align);
	}

	/**
	 * ZK 3.6 {@code Row.spans} declared the column span of each row child.
	 * ZK CE 10 expresses the same layout through {@link Cell#setColspan(int)},
	 * so the legacy comma separated list is applied to the {@link Cell}
	 * children that are present. Non-cell children keep the CE default span of
	 * one column; restoring the remaining legacy spans is a Phase 5g concern.
	 *
	 * @param row target row, may be null
	 * @param spans legacy comma separated spans
	 */
	public static void setSpans(Row row, String spans)
	{
		if (row == null || spans == null || spans.trim().length() == 0)
			return;
		String[] values = spans.split(",");
		int index = 0;
		for (Component child : row.getChildren())
		{
			if (index >= values.length)
				break;
			if (child instanceof Cell)
			{
				try
				{
					((Cell) child).setColspan(Integer.parseInt(values[index].trim()));
				}
				catch (NumberFormatException e)
				{
					log.log(Level.FINE, "Ignoring non numeric legacy span " + values[index], e);
				}
			}
			index++;
		}
	}

	/**
	 * ZK 3.6 {@code widths} distributed explicit widths over the children of a
	 * box or row. ZK CE 10 sets the width on the children themselves.
	 *
	 * @param parent component owning the children, may be null
	 * @param widths legacy comma separated widths
	 */
	public static void setWidths(Component parent, String widths)
	{
		if (parent == null || widths == null)
			return;
		String[] values = widths.split(",");
		int index = 0;
		for (Component child : parent.getChildren())
		{
			if (index >= values.length)
				break;
			if (child instanceof HtmlBasedComponent)
			{
				String width = values[index].trim();
				((HtmlBasedComponent) child).setWidth(width.length() == 0 ? null : width);
			}
			index++;
		}
	}

	/**
	 * ZK 3.6 {@code fixedLayout} is the inverse of the ZK CE 10
	 * {@code sizedByContent} property of every mesh element.
	 *
	 * @param mesh grid, listbox or tree, may be null
	 * @param fixedLayout legacy fixed layout flag
	 */
	public static void setFixedLayout(MeshElement mesh, boolean fixedLayout)
	{
		if (mesh == null)
			return;
		mesh.setSizedByContent(!fixedLayout);
	}

	/**
	 * ZK 3.6 {@code Panel.framable} became {@code Panel.border} in ZK CE 10.
	 *
	 * @param panel target panel, may be null
	 * @param framable legacy framable flag
	 */
	public static void setFramable(Panel panel, boolean framable)
	{
		if (panel == null)
			return;
		panel.setBorder(framable);
	}

	/**
	 * ZK 3.6 {@code Groupbox.legend} selected the legend rendering of the 3d
	 * mold. ZK CE 10 keeps the same distinction between the {@code 3d} and
	 * {@code default} molds.
	 *
	 * @param groupbox target group box, may be null
	 * @param legend legacy legend flag
	 */
	public static void setLegend(Groupbox groupbox, boolean legend)
	{
		if (groupbox == null)
			return;
		groupbox.setMold(legend ? "3d" : "default");
	}
}
