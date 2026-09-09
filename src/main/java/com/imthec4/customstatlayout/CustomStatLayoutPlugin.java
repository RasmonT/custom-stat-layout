/*
 * Copyright (c) 2026, ImTheC4
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.imthec4.customstatlayout;

import com.google.inject.Provides;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Publishes the four orb values, and the two regeneration timers that sweep around
 * them, as JSON on the loopback interface. A browser-source overlay can then draw its
 * own orbs instead of cropping the client's -- which is the only way to get a fill
 * behind the icons, since the game paints icon and fill into the same pixels.
 *
 * Everything the game shows in those orbs changes on a game tick, so the snapshot is
 * taken from tick and stat/var events, all of which arrive on the client thread. The
 * HTTP worker threads never touch the client; they only read the string built here.
 *
 * The ids below are spelled out as plain constants rather than pulled from RuneLite's
 * generated id classes, so this compiles against any client version of the last few
 * years. The name in each comment is where the value came from.
 */
@Slf4j
@PluginDescriptor(
	name = "Custom Stat Layout",
	description = "Serves live HP, Prayer, run energy and special attack as JSON on 127.0.0.1 for stream overlays",
	tags = {"stream", "streaming", "obs", "overlay", "hud", "stats", "orbs"}
)
public class CustomStatLayoutPlugin extends Plugin
{
	/** VarPlayerID.SA_ENERGY -- special attack energy in tenths of a percent, so 1000 is full. */
	private static final int VARP_SPECIAL_ATTACK = 300;
	/**
	 * VarPlayerID.SOULREAPER_STACKS -- soul stacks, 0-5. The game replaces the number in
	 * the special attack orb with this while a soulreaper axe is wielded.
	 */
	private static final int VARP_SOULREAPER_STACKS = 3784;
	/** VarbitID.PRAYER_RAPIDHEAL. */
	private static final int VARBIT_RAPID_HEAL = 4111;
	/** VarbitID.STAMINA_ACTIVE -- the run orb shows a stamina potion is running. */
	private static final int VARBIT_STAMINA = 25;
	/** VarPlayerID.SA_ENABLED -- the special attack orb is lit because the attack is armed. */
	private static final int VARP_SPECIAL_ENABLED = 301;
	/**
	 * VarPlayerID.POISON. Negative is an immunity timer, zero is clean, positive is poisoned,
	 * and at or above the threshold it is venom. The game tints the hitpoints orb for each.
	 */
	private static final int VARP_POISON = 102;
	private static final int VENOM_THRESHOLD = 1000000;
	/** VarPlayerID.DISEASE. */
	private static final int VARP_DISEASE = 456;
	/** gameval InventoryID.WORN -- the equipment container. */
	private static final int INV_WORN = 94;
	/** gameval ItemID: soulreaper axe, and its ornamented variant. */
	private static final int SOULREAPER_AXE = 28338;
	private static final int SOULREAPER_AXE_ORN = 33335;
	/** gameval ItemID.LIGHTBEARER -- the ring that doubles special attack regeneration. */
	private static final int LIGHTBEARER = 25975;
	private static final int MAX_SOUL_STACKS = 5;

	/** values() allocates a fresh array on every call, and this is read on every event. */
	private static final Prayer[] PRAYERS = Prayer.values();
	/** One game tick, in milliseconds. */
	private static final int GAME_TICK_MS = 600;

	// Regeneration cycles, in game ticks. Same numbers as RuneLite's Regeneration Meter.
	private static final int SPEC_REGEN_TICKS = 50;
	private static final int HP_REGEN_TICKS = 100;

	/**
	 * How many consecutive ports to try before giving up. A second client - someone
	 * playing two accounts at once - cannot bind the port the first one took, so it
	 * moves up to the next free one instead of serving nothing.
	 */
	private static final int PORT_SCAN_RANGE = 10;

	@Inject
	private Client client;

	@Inject
	private CustomStatLayoutConfig config;

	@Inject
	private ClientThread clientThread;

	private volatile String snapshot = "{\"ok\":false,\"loggedIn\":false}";
	private CustomStatLayoutServer server;

	private int ticksSinceSpecRegen;
	private int ticksSinceHpRegen;
	private boolean wearingLightbearer;
	private double hpRegen;
	private double specRegen;
	/** Counts game ticks seen. If this stays at 0 while the other values move, ticks are not arriving. */
	private long ticks;
	/**
	 * Wall-clock time of the last game tick. An overlay on this machine reads the same clock,
	 * so it can work out how far through the current tick it is and animate in step with the
	 * game rather than with its own polling.
	 */
	private long tickTs;
	/** Set if the regeneration maths ever throws, so the fault shows up in the JSON instead of only in the log. */
	private volatile String regenError;

	@Provides
	CustomStatLayoutConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CustomStatLayoutConfig.class);
	}

	@Override
	protected void startUp()
	{
		startServer();
		// Plugins start on the Swing thread, and no game event need arrive for a long time
		// afterwards - at the login screen, none ever does. Without this the snapshot would
		// sit at its useless initial value until the player logged in.
		clientThread.invokeLater(this::update);
	}

	@Override
	protected void shutDown()
	{
		stopServer();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CustomStatLayoutConfig.GROUP.equals(event.getGroup())
			&& CustomStatLayoutConfig.KEY_PORT.equals(event.getKey()))
		{
			stopServer();
			startServer();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		ticks++;
		tickTs = System.currentTimeMillis();
		try
		{
			advanceRegen();
		}
		catch (RuntimeException e)
		{
			// Keep serving the numbers even if the regeneration maths breaks, and say so in
			// the JSON, so a fault here is visible without digging through the client log.
			if (regenError == null)
			{
				regenError = e.getClass().getSimpleName() + ": " + e.getMessage();
				log.warn("Custom Stat Layout: regeneration tracking failed", e);
			}
		}
		update();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		update();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VARBIT_RAPID_HEAL)
		{
			// Toggling Rapid Heal restarts the cycle rather than rescaling it
			ticksSinceHpRegen = 0;
		}
		update();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != INV_WORN)
		{
			return;
		}

		ItemContainer equipment = event.getItemContainer();
		boolean hasLightbearer = equipment != null && equipment.contains(LIGHTBEARER);
		if (hasLightbearer != wearingLightbearer)
		{
			// Swapping the ring keeps the remaining time if under 25 ticks are left.
			// Taking it off always lands on 0.
			ticksSinceSpecRegen = Math.max(0, ticksSinceSpecRegen - 25);
			wearingLightbearer = hasLightbearer;
		}
		update();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			ticksSinceHpRegen = -2;   // the client's own meter uses this offset to stay in step
			ticksSinceSpecRegen = 0;
		}
		update();
	}

	/**
	 * Advances the two regeneration cycles by one tick. Must run exactly once per game
	 * tick, which is why it hangs off onGameTick and not off update().
	 */
	private void advanceRegen()
	{
		int ticksPerSpecRegen = wearingLightbearer ? SPEC_REGEN_TICKS / 2 : SPEC_REGEN_TICKS;
		if (client.getVarpValue(VARP_SPECIAL_ATTACK) >= 1000)
		{
			// A full bar does not tick
			ticksSinceSpecRegen = 0;
		}
		else
		{
			ticksSinceSpecRegen = (ticksSinceSpecRegen + 1) % ticksPerSpecRegen;
		}
		specRegen = ticksSinceSpecRegen / (double) ticksPerSpecRegen;

		int ticksPerHpRegen = client.isPrayerActive(Prayer.RAPID_HEAL) ? HP_REGEN_TICKS / 2 : HP_REGEN_TICKS;
		ticksSinceHpRegen = (ticksSinceHpRegen + 1) % ticksPerHpRegen;
		hpRegen = ticksSinceHpRegen / (double) ticksPerHpRegen;

		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int hpMax = client.getRealSkillLevel(Skill.HITPOINTS);
		if (hp == hpMax && !config.hpRegenAtFullHealth())
		{
			// Nothing is being healed, so the game shows nothing
			hpRegen = 0;
		}
		else if (hp > hpMax)
		{
			// Overhealed: the next tick takes a point away, so the sweep runs backwards
			hpRegen = 1 - hpRegen;
		}
	}

	private void startServer()
	{
		int base = config.port();
		for (int port = base; port < base + PORT_SCAN_RANGE && port <= 65535; port++)
		{
			try
			{
				server = new CustomStatLayoutServer(port, () -> snapshot);
				log.info("Custom Stat Layout listening on http://127.0.0.1:{}/stats.json", server.port());
				return;
			}
			catch (IOException e)
			{
				log.debug("Custom Stat Layout could not open port {}", port, e);
			}
		}

		server = null;
		log.warn("Custom Stat Layout could not open any port between {} and {}. Another program is probably "
			+ "using them; pick a different port in the plugin settings.", base, base + PORT_SCAN_RANGE - 1);
	}

	private void stopServer()
	{
		if (server != null)
		{
			server.stop();
			server = null;
		}
	}

	/**
	 * Rebuilds the snapshot. Called only from client-thread events.
	 */
	private void update()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			// Explicit rather than zeroed: an overlay usually wants to hold the last frame
			// through a hop rather than flash empty orbs.
			snapshot = "{\"ok\":true,\"loggedIn\":false,\"ticks\":" + ticks + "}";
			return;
		}

		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int hpMax = client.getRealSkillLevel(Skill.HITPOINTS);
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int prayerMax = client.getRealSkillLevel(Skill.PRAYER);

		// getEnergy() is in hundredths of a percent; the orb shows the whole percent.
		int runRaw = client.getEnergy();
		int specRaw = client.getVarpValue(VARP_SPECIAL_ATTACK);
		int souls = client.getVarpValue(VARP_SOULREAPER_STACKS);
		boolean soulreaper = isSoulreaperEquipped();

		StringBuilder b = new StringBuilder(400);
		b.append("{\"ok\":true,\"loggedIn\":true");
		b.append(",\"hp\":").append(hp).append(",\"hpMax\":").append(hpMax);
		b.append(",\"prayer\":").append(prayer).append(",\"prayerMax\":").append(prayerMax);
		b.append(",\"run\":").append(runRaw / 100).append(",\"runRaw\":").append(runRaw);
		b.append(",\"spec\":").append(specRaw / 10).append(",\"specRaw\":").append(specRaw);
		b.append(",\"souls\":").append(souls).append(",\"soulsMax\":").append(MAX_SOUL_STACKS);
		b.append(",\"specMode\":\"").append(soulreaper ? "souls" : "percent").append('"');
		// How far through the current regeneration cycle each orb is, 0..1. This is the
		// sweep the game draws around the orb, not a value the player can read as a number.
		b.append(",\"hpRegen\":").append(round3(hpRegen));
		b.append(",\"specRegen\":").append(round3(specRegen));
		b.append(",\"lightbearer\":").append(wearingLightbearer);
		b.append(",\"ticks\":").append(ticks);
		b.append(",\"tickTs\":").append(tickTs);
		b.append(",\"tickMs\":").append(GAME_TICK_MS);
		b.append(",\"praying\":").append(isAnyPrayerActive());
		// State the game paints onto the orbs themselves, so a layout can do the same
		b.append(",\"specArmed\":").append(client.getVarpValue(VARP_SPECIAL_ENABLED) == 1);
		b.append(",\"stamina\":").append(client.getVarbitValue(VARBIT_STAMINA) == 1);
		b.append(",\"weight\":").append(client.getWeight());
		b.append(",\"poison\":\"").append(poisonState()).append('"');
		b.append(",\"diseased\":").append(client.getVarpValue(VARP_DISEASE) > 0);
		if (regenError != null)
		{
			b.append(",\"regenError\":");
			appendJsonString(b, regenError);
		}
		b.append(",\"rapidHeal\":").append(client.isPrayerActive(Prayer.RAPID_HEAL));
		b.append(",\"ts\":").append(System.currentTimeMillis());
		b.append('}');

		snapshot = b.toString();
	}

	/** Three decimals is finer than a single tick of either cycle, and keeps the JSON short. */
	private static double round3(double v)
	{
		return Math.round(v * 1000d) / 1000d;
	}

	/**
	 * How the game would tint the hitpoints orb: clean, immune while an antidote holds,
	 * poisoned, or venomed.
	 */
	private String poisonState()
	{
		int v = client.getVarpValue(VARP_POISON);
		if (v >= VENOM_THRESHOLD)
		{
			return "venom";
		}
		if (v > 0)
		{
			return "poison";
		}
		return v < 0 ? "immune" : "none";
	}

	/**
	 * Whether any prayer is on. The prayer flick helper is hidden when none is, the same way
	 * RuneLite's own does it, so an overlay needs to know.
	 */
	private boolean isAnyPrayerActive()
	{
		for (Prayer prayer : PRAYERS)
		{
			if (client.isPrayerActive(prayer))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isSoulreaperEquipped()
	{
		ItemContainer equipment = client.getItemContainer(INV_WORN);
		if (equipment == null)
		{
			return false;
		}
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return false;
		}
		int id = weapon.getId();
		return id == SOULREAPER_AXE || id == SOULREAPER_AXE_ORN;
	}

	private static void appendJsonString(StringBuilder b, String s)
	{
		if (s == null)
		{
			b.append("null");
			return;
		}
		b.append('"');
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			switch (c)
			{
				case '"':
					b.append("\\\"");
					break;
				case '\\':
					b.append("\\\\");
					break;
				case '\n':
					b.append("\\n");
					break;
				case '\r':
					b.append("\\r");
					break;
				case '\t':
					b.append("\\t");
					break;
				default:
					if (c < 0x20)
					{
						b.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						b.append(c);
					}
			}
		}
		b.append('"');
	}
}
