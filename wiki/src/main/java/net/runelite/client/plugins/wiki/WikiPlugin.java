/*
 * Copyright (c) 2018 Abex
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
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.wiki;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuOpcode;
import net.runelite.api.NPC;
import net.runelite.api.NPCDefinition;
import net.runelite.api.ObjectDefinition;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetHiddenChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.util.Text;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Wiki",
	description = "Adds a Wiki button that takes you to the OSRS Wiki",
	type = PluginType.UTILITY
)
@Slf4j
public class WikiPlugin extends Plugin
{
	private static final int[] QUESTLIST_WIDGET_IDS = new int[]
		{
			WidgetInfo.QUESTLIST_FREE_CONTAINER.getId(),
			WidgetInfo.QUESTLIST_MEMBERS_CONTAINER.getId(),
			WidgetInfo.QUESTLIST_MINIQUEST_CONTAINER.getId(),
		};

	static final HttpUrl WIKI_BASE = HttpUrl.parse("https://oldschool.runescape.wiki");
	static final HttpUrl WIKI_API = WIKI_BASE.newBuilder().addPathSegments("api.php").build();
	static final String UTM_SOURCE_KEY = "utm_source";
	static final String UTM_SOURCE_VALUE = "runelite";

	private static final String MENUOP_GUIDE = "Guide";
	private static final String MENUOP_QUICKGUIDE = "Quick Guide";
	private static final String MENUOP_WIKI = "Wiki";

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private WikiConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Provider<WikiSearchChatboxTextInput> wikiSearchChatboxTextInputProvider;

	private Widget icon;

	private boolean wikiSelected = false;

	static final String CONFIG_GROUP_KEY = "wiki";

	@Provides
	WikiConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiConfig.class);
	}

	@Override
	public void startUp()
	{
		clientThread.invokeLater(this::addWidgets);
	}

	@Override
	public void shutDown()
	{
		clientThread.invokeLater(this::removeWidgets);
	}

	private void removeWidgets()
	{

		Widget minimapOrbs = client.getWidget(WidgetInfo.MINIMAP_ORBS);
		if (minimapOrbs == null)
		{
			return;
		}
		Widget[] children = minimapOrbs.getChildren();
		if (children == null || children.length < 1)
		{
			return;
		}
		children[0] = null;

		Widget vanilla = client.getWidget(WidgetInfo.MINIMAP_WIKI_BANNER);
		if (vanilla != null)
		{
			vanilla.setHidden(false);
		}

		onDeselect();
		client.setSpellSelected(false);
	}

	@Subscribe
	private void onWidgetLoaded(WidgetLoaded l)
	{
		if (l.getGroupId() == WidgetID.MINIMAP_GROUP_ID)
		{
			addWidgets();
		}
	}

	private void addWidgets()
	{
		Widget minimapOrbs = client.getWidget(WidgetInfo.MINIMAP_ORBS);
		if (minimapOrbs == null)
		{
			return;
		}

		Widget vanilla = client.getWidget(WidgetInfo.MINIMAP_WIKI_BANNER);
		if (vanilla != null)
		{
			vanilla.setHidden(true);
		}

		icon = minimapOrbs.createChild(0, WidgetType.GRAPHIC);
		icon.setSpriteId(SpriteID.WIKI_DESELECTED);
		icon.setOriginalX(0);
		icon.setOriginalY(0);
		icon.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		icon.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
		icon.setOriginalWidth(40);
		icon.setOriginalHeight(14);
		icon.setTargetVerb("Lookup");
		icon.setName("Wiki");
		icon.setClickMask(WidgetConfig.USE_GROUND_ITEM | WidgetConfig.USE_ITEM | WidgetConfig.USE_NPC
			| WidgetConfig.USE_OBJECT | WidgetConfig.USE_WIDGET);
		icon.setNoClickThrough(true);
		icon.setOnTargetEnterListener((JavaScriptCallback) ev ->
		{
			wikiSelected = true;
			icon.setSpriteId(SpriteID.WIKI_SELECTED);
			client.setAllWidgetsAreOpTargetable(true);
		});

		final int searchIndex = config.leftClickSearch() ? 4 : 5;
		icon.setAction(searchIndex, "Search");
		icon.setOnOpListener((JavaScriptCallback) ev ->
		{
			if (ev.getOp() == searchIndex + 1)
			{
				openSearchInput();
			}
		});

		// This doesn't always run because we cancel the menuop
		icon.setOnTargetLeaveListener((JavaScriptCallback) ev -> onDeselect());
		icon.revalidate();
	}

	@Subscribe
	private void onWidgetHiddenChanged(WidgetHiddenChanged ev)
	{
		if (ev.getWidget().getId() == WidgetInfo.MINIMAP_WIKI_BANNER.getId())
		{
			ev.getWidget().setHidden(true);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CONFIG_GROUP_KEY))
		{
			clientThread.invokeLater(() ->
			{
				removeWidgets();
				addWidgets();
			});
		}
	}

	private void onDeselect()
	{
		client.setAllWidgetsAreOpTargetable(false);

		wikiSelected = false;
		if (icon != null)
		{
			icon.setSpriteId(SpriteID.WIKI_DESELECTED);
		}
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked ev)
	{
		if (ev.getMenuOpcode() == MenuOpcode.RUNELITE)
		{
			checkQuestClicked(ev);
		}

		if (!wikiSelected)
		{
			return;
		}

		onDeselect();
		client.setSpellSelected(false);
		ev.consume();

		String type;
		int id;
		String name;
		WorldPoint location;

		switch (ev.getMenuOpcode())
		{
			case RUNELITE:
			case CANCEL:
				return;
			case ITEM_USE_ON_WIDGET:
			case SPELL_CAST_ON_GROUND_ITEM:
			{
				type = "item";
				id = itemManager.canonicalize(ev.getIdentifier());
				name = itemManager.getItemDefinition(id).getName();
				location = null;
				break;
			}
			case SPELL_CAST_ON_NPC:
			{
				type = "npc";
				NPC npc = client.getCachedNPCs()[ev.getIdentifier()];
				NPCDefinition nc = npc.getTransformedDefinition();
				id = nc.getId();
				name = nc.getName();
				location = npc.getWorldLocation();
				break;
			}
			case SPELL_CAST_ON_GAME_OBJECT:
			{
				type = "object";
				ObjectDefinition lc = client.getObjectDefinition(ev.getIdentifier());
				if (lc.getImpostorIds() != null)
				{
					lc = lc.getImpostor();
				}
				id = lc.getId();
				name = lc.getName();
				location = WorldPoint.fromScene(client, ev.getParam0(), ev.getParam1(), client.getPlane());
				break;
			}
			case SPELL_CAST_ON_WIDGET:
			{
				Widget w = getWidget(ev.getParam1(), ev.getParam0());

				if (w.getType() == WidgetType.GRAPHIC && w.getItemId() != -1)
				{
					type = "item";
					id = itemManager.canonicalize(w.getItemId());
					name = itemManager.getItemDefinition(id).getName();
					location = null;
					break;
				}
			}
				// fallthrough
			default:
				log.info("Unknown menu option: {} {} {}", ev, ev.getMenuOpcode(), ev.getMenuOpcode() == MenuOpcode.CANCEL);
				return;
		}

		name = Text.removeTags(name);
		HttpUrl.Builder urlBuilder = WIKI_BASE.newBuilder();
		urlBuilder.addPathSegments("w/Special:Lookup")
			.addQueryParameter("type", type)
			.addQueryParameter("id", "" + id)
			.addQueryParameter("name", name)
			.addQueryParameter(UTM_SOURCE_KEY, UTM_SOURCE_VALUE);

		if (location != null)
		{
			urlBuilder.addQueryParameter("x", "" + location.getX())
				.addQueryParameter("y", "" + location.getY())
				.addQueryParameter("plane", "" + location.getPlane());
		}

		HttpUrl url = urlBuilder.build();

		LinkBrowser.browse(url.toString());
	}

	private void checkQuestClicked(MenuOptionClicked ev)
	{
		boolean quickguide = false;
		switch (ev.getOption())
		{
			case MENUOP_QUICKGUIDE:
				quickguide = true;
				//fallthrough;
			case MENUOP_GUIDE:
				ev.consume();
				String quest = Text.removeTags(ev.getTarget());
				HttpUrl.Builder ub = WIKI_BASE.newBuilder()
					.addPathSegment("w")
					.addPathSegment(quest)
					.addQueryParameter(UTM_SOURCE_KEY, UTM_SOURCE_VALUE);
				if (quickguide)
				{
					ub.addPathSegment("Quick_guide");
				}
				LinkBrowser.browse(ub.build().toString());
				break;
			case MENUOP_WIKI:
				LinkBrowser.browse(WIKI_BASE.newBuilder()
					.addPathSegment("w")
					.addPathSegment(Text.removeTags(ev.getTarget()))
					.addQueryParameter(UTM_SOURCE_KEY, UTM_SOURCE_VALUE)
					.build().toString());
		}
	}

	private void openSearchInput()
	{
		wikiSearchChatboxTextInputProvider.get()
			.build();
	}

	@Subscribe
	private void onMenuEntryAdded(MenuEntryAdded event)
	{
		int widgetIndex = event.getParam0();
		int widgetID = event.getParam1();

		if (wikiSelected && event.getOpcode() == MenuOpcode.SPELL_CAST_ON_WIDGET.getId())
		{
			Widget w = getWidget(widgetID, widgetIndex);
			if (!(w.getType() == WidgetType.GRAPHIC && w.getItemId() != -1))
			{
				// we don't support this widget
				// remove the last SPELL_CAST_ON_WIDGET; we can't blindly remove the top action because some other
				// plugin might have added something on this same event, and we probably shouldn't remove that instead
				MenuEntry[] menuEntries = client.getMenuEntries();
				for (int i = menuEntries.length - 1; i >= 0; i--)
				{
					if (menuEntries[i].getOpcode() == MenuOpcode.SPELL_CAST_ON_WIDGET.getId())
					{
						menuEntries[i] = null;
						client.setMenuEntries(menuEntries);
						break;
					}
				}
			}
		}

		if (WidgetInfo.TO_GROUP(widgetID) == WidgetInfo.SKILLS_CONTAINER.getGroupId()
			&& event.getOption().contains("View"))
		{
			Widget w = getWidget(widgetID, widgetIndex);
			if (w.getParentId() != WidgetInfo.SKILLS_CONTAINER.getId())
			{
				return;
			}

			String action = firstAction(w);
			if (action == null)
			{
				return;
			}

			client.insertMenuItem(
				MENUOP_WIKI,
				action.replace("View ", "").replace("Guide ", ""),
				MenuOpcode.RUNELITE.getId(),
				0,
				widgetIndex,
				widgetID,
				false
			);
		}
	}

	private Widget getWidget(int wid, int index)
	{
		Widget w = client.getWidget(WidgetInfo.TO_GROUP(wid), WidgetInfo.TO_CHILD(wid));
		if (index != -1)
		{
			w = w.getChild(index);
		}
		return w;
	}

	@Nullable
	private static String firstAction(Widget widget)
	{
		for (String action : widget.getActions())
		{
			if (StringUtils.isNotEmpty(action))
			{
				return action;
			}
		}

		return null;
	}
}
