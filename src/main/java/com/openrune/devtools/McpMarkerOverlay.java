/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE.
 */
package com.openrune.devtools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Visual markers the mark_tile/mark_widget tools draw so a human watching the
 * client can see what an automated agent is pointing at: outlined tiles and
 * widget rectangles, with optional labels and tick-based expiry.
 */
class McpMarkerOverlay extends Overlay
{
	static final int DEFAULT_COLOR = 0xFFDD00;

	private static final class Marker
	{
		String name;
		String label;
		int color;
		int expireTick; // -1 = never
		// tile marker
		Integer worldX, worldY, plane;
		// widget marker
		Integer componentId, childIndex;
	}

	private final Client client;
	private final Map<String, Marker> markers = new HashMap<>();

	@Inject
	McpMarkerOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	synchronized void markTile(String name, int x, int y, int plane, String label,
		int color, int ticks)
	{
		Marker m = new Marker();
		m.name = name;
		m.label = label;
		m.color = color;
		m.expireTick = ticks > 0 ? client.getTickCount() + ticks : -1;
		m.worldX = x;
		m.worldY = y;
		m.plane = plane;
		markers.put(name, m);
	}

	synchronized void markWidget(String name, int componentId, Integer childIndex,
		String label, int color, int ticks)
	{
		Marker m = new Marker();
		m.name = name;
		m.label = label;
		m.color = color;
		m.expireTick = ticks > 0 ? client.getTickCount() + ticks : -1;
		m.componentId = componentId;
		m.childIndex = childIndex;
		markers.put(name, m);
	}

	synchronized int clear(String name)
	{
		if (name == null)
		{
			int n = markers.size();
			markers.clear();
			return n;
		}
		return markers.remove(name) != null ? 1 : 0;
	}

	synchronized int count()
	{
		prune();
		return markers.size();
	}

	private void prune()
	{
		int tick = client.getTickCount();
		markers.values().removeIf(m -> m.expireTick >= 0 && tick > m.expireTick);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		synchronized (this)
		{
			prune();
			for (Marker m : markers.values())
			{
				Color color = new Color(m.color);
				if (m.worldX != null)
				{
					renderTile(g, m, color);
				}
				else if (m.componentId != null)
				{
					renderWidget(g, m, color);
				}
			}
		}
		return null;
	}

	private void renderTile(Graphics2D g, Marker m, Color color)
	{
		LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(m.worldX, m.worldY,
			m.plane != null ? m.plane : client.getPlane()));
		if (lp == null)
		{
			return;
		}
		Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly == null)
		{
			return;
		}
		g.setColor(color);
		g.setStroke(new BasicStroke(2));
		g.drawPolygon(poly);
		g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
		g.fillPolygon(poly);
		drawLabel(g, m.label, poly.getBounds(), color);
	}

	private void renderWidget(Graphics2D g, Marker m, Color color)
	{
		Widget w = client.getWidget(m.componentId);
		if (w != null && m.childIndex != null)
		{
			Widget[] children = w.getDynamicChildren();
			w = children != null && m.childIndex >= 0 && m.childIndex < children.length
				? children[m.childIndex] : null;
		}
		if (w == null || w.isHidden())
		{
			return;
		}
		Rectangle bounds = w.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return;
		}
		g.setColor(color);
		g.setStroke(new BasicStroke(2));
		g.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
		drawLabel(g, m.label, bounds, color);
	}

	private void drawLabel(Graphics2D g, String label, Rectangle bounds, Color color)
	{
		String text = label != null ? label : "";
		if (text.isEmpty())
		{
			return;
		}
		g.setColor(Color.BLACK);
		g.drawString(text, bounds.x + 1, bounds.y - 3);
		g.setColor(color);
		g.drawString(text, bounds.x, bounds.y - 4);
	}
}
