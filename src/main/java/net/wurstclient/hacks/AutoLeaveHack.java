/*
 * Copyright (c) 2014-2023 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.FakePlayerEntity;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SearchTags({"auto leave", "AutoDisconnect", "auto disconnect", "AutoQuit",
	"auto quit"})
public final class AutoLeaveHack extends Hack implements UpdateListener
{
	private final SliderSetting health = new SliderSetting("Health",
		"Leaves the server when your health reaches this value or falls below it.",
		4, 0.5, 9.5, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	public final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lQuit\u00a7r mode just quits the game normally.\n"
			+ "Bypasses NoCheat+ but not CombatLog.\n\n"
			+ "\u00a7lChars\u00a7r mode sends a special chat message that causes the server to kick you.\n"
			+ "Bypasses NoCheat+ and some versions of CombatLog.\n\n"
			+ "\u00a7lTP\u00a7r mode teleports you to an invalid location, causing the server to kick you.\n"
			+ "Bypasses CombatLog, but not NoCheat+.\n\n"
			+ "\u00a7lSelfHurt\u00a7r mode sends the packet for attacking another player, but with yourself as both the attacker and the target. This causes the server to kick you.\n"
			+ "Bypasses both CombatLog and NoCheat+.",
		Mode.values(), Mode.QUIT);

	private final CheckboxSetting leaveIfPlayer = new CheckboxSetting(
			"Leave if a player appears",
			"Leave the game if a player comes into your render distance.",
			false);

	private final ArrayList<PlayerEntity> players = new ArrayList<>();

	public AutoLeaveHack()
	{
		super("AutoLeave");
		
		setCategory(Category.COMBAT);
		addSetting(health);
		addSetting(mode);
		addSetting(leaveIfPlayer);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	public void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		boolean shouldLeave = false;
		// check gamemode
		if(MC.player.getAbilities().creativeMode)
			return;
		
		// check for other players
		if(MC.isInSingleplayer()
			&& MC.player.networkHandler.getPlayerList().size() == 1)
			return;
		
		// check health
		if(MC.player.getHealth() < health.getValueF() * 2F)
			shouldLeave = true;

		if (leaveIfPlayer.isChecked()) {
			players.clear();
			Stream<AbstractClientPlayerEntity> stream = MC.world.getPlayers()
					.parallelStream().filter(e -> !e.isRemoved() && e.getHealth() > 0)
					.filter(e -> e != MC.player)
					.filter(e -> !(e instanceof FakePlayerEntity))
					.filter(e -> Math.abs(e.getY() - MC.player.getY()) <= 1e6);
			players.addAll(stream.collect(Collectors.toList()));
			if (!players.isEmpty()) {
				shouldLeave = true;
			}
		}

		if (!shouldLeave) return;

		// leave server
		switch(mode.getSelected())
		{
			case QUIT:
			MC.world.disconnect();
			break;
			
			case CHARS:
			MC.getNetworkHandler().sendChatMessage("\u00a7");
			break;
			
			case TELEPORT:
			MC.player.networkHandler
				.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(3.1e7,
					100, 3.1e7, false));
			break;
			
			case SELFHURT:
			MC.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket
				.attack(MC.player, MC.player.isSneaking()));
			break;
		}
		
		// disable
		setEnabled(false);
	}
	
	public static enum Mode
	{
		QUIT("Quit"),
		
		CHARS("Chars"),
		
		TELEPORT("TP"),
		
		SELFHURT("SelfHurt");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
