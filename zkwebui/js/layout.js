/*
 * ADempiere web client layout helpers.
 *
 * Phase 5d: migrated from the ZK 3.6 client API to ZK CE 10.
 *
 * The ZK 3.6 version of this file used two globals that ZK CE 10 removed:
 *
 *   $e(uuid)          the ZK 3.6 "element by uuid" helper. ZK CE 10 exposes
 *                     widgets through zk.Widget.$ and their DOM node through
 *                     $n(); document.getElementById is equivalent for a bound
 *                     widget and does not depend on a ZK internal.
 *   zkau.getMeta(...) the ZK 3.6 deferred-render metadata accessor. ZK CE 10
 *                     has no zkau object at all.
 *
 * scrollToRow is on the Phase 5d walking-skeleton route: GridPanel.java:771,780
 * sends it on every row change, so under the ZK 3.6 version the modern runtime
 * raised "ReferenceError: $e is not defined" on every grid navigation.
 */

/**
 * Resolves a bound ZK widget's DOM node from its uuid.
 */
function ad_node(uuid) {
	if (!uuid) {
		return null;
	}
	if (window.zk && zk.Widget && typeof zk.Widget.$ === 'function') {
		var widget = zk.Widget.$('#' + uuid);
		if (widget && typeof widget.$n === 'function') {
			var node = widget.$n();
			if (node) {
				return node;
			}
		}
	}
	return document.getElementById(uuid);
}

/*
 * ZK 3.6 deferred border-layout render.
 *
 * ZK CE 10 lays out Borderlayout itself and exposes no render metadata, so the
 * deferred render becomes ZK CE's own resize notification. The callers are
 * WTask and WTreeMaintenance; neither is on the Phase 5d route, and Phase 5g
 * screen parity owns confirming the visual outcome.
 */
function ad_deferRenderBorderLayout(uuid, timeout) {
	setTimeout(function () {
		_ad_deferBDL(uuid);
	}, timeout);
}

function _ad_deferBDL(uuid) {
	if (!ad_node(uuid)) {
		return;
	}
	if (window.zk && zk.Widget && typeof zk.Widget.$ === 'function'
			&& window.zUtl && typeof zUtl.fireSized === 'function') {
		var widget = zk.Widget.$('#' + uuid);
		if (widget) {
			zUtl.fireSized(widget);
		}
	}
}

/*
 * Closes every open bubble of a timeline widget.
 *
 * The timeline gadget is a ZK 3.x org.zkforge add-on that Phase 5d does not
 * migrate; org.adempiere.webui.TimelineEventFeed and its /timeline route are
 * owned by Phase 5f. The function stays so the shared script file loads
 * unchanged, and it now exits quietly when the widget is absent.
 */
function ad_closeBuble(uuid) {
	var cmp = ad_node(uuid);
	if (!cmp || !cmp.bandInfos || !cmp.instance) {
		return;
	}
	for (var i = 0; i < cmp.bandInfos.length; i++) {
		cmp.instance.getBand(i).closeBubble();
	}
}

/*
 * Scrolls the current grid row into view. Sent by GridPanel on every row
 * change, so this runs throughout the Phase 5d window flow.
 */
function scrollToRow(uuid) {
	var cmp = ad_node(uuid);
	if (cmp == null) {
		return;
	}
	cmp.style.display = "inline";
	cmp.focus();
	cmp.style.display = "none";
}
