/*
 * Copyright (c) 2021, Obasill <https://github.com/Obasill>
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
package net.runelite.client.plugins.microbot.questhelper.helpers.achievementdiaries.lumbridgeanddraynor;

import net.runelite.client.plugins.microbot.questhelper.bank.banktab.BankSlotIcons;
import net.runelite.client.plugins.microbot.questhelper.collections.ItemCollections;
import net.runelite.client.plugins.microbot.questhelper.panel.PanelDetails;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.ComplexStateQuestHelper;
import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.questhelper.requirements.ChatMessageRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.conditional.Conditions;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.player.SkillRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.quest.QuestRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.util.LogicType;
import net.runelite.client.plugins.microbot.questhelper.requirements.var.VarbitRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.var.VarplayerRequirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.zone.Zone;
import net.runelite.client.plugins.microbot.questhelper.requirements.zone.ZoneRequirement;
import net.runelite.client.plugins.microbot.questhelper.rewards.ItemReward;
import net.runelite.client.plugins.microbot.questhelper.rewards.UnlockReward;
import net.runelite.client.plugins.microbot.questhelper.steps.*;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LumbridgeEasy extends ComplexStateQuestHelper
{
	// Items required
	ItemRequirement combatGear, lightSource, rope, runeEss, axe, tinderbox, smallFishingNet, pickaxe,
		waterAccessOrAbyss, spinyHelm, dough, oakLogs;

	// Items recommended
	ItemRequirement food;

	// Quests required
	Requirement runeMysteries, cooksAssistant;

	Requirement notDrayAgi, notKillCaveBug, notSedridor, notWaterRune, notHans, notPickpocket, notOak, notKillZombie,
		notFishAnchovies, notBread, notIron, notEnterHAM, choppedLogs;

	Requirement addedRopeToHole;

	QuestStep claimReward, drayAgi, killCaveBug, addRopeToHole, moveToDarkHole, sedridor, moveToSed, moveToWaterAltar,
		waterRune, hans, chopOak, burnOak, fishAnchovies, bread, mineIron;

	NpcStep pickpocket, killZombie;

	ObjectStep moveToDraySewer, enterHAM;

	Zone cave, sewer, water, mageTower, lumby;

	ZoneRequirement inCave, inSewer, inWater, inMageTower, inLumby;

	ConditionalStep drayAgiTask, killCaveBugTask, sedridorTask, waterRuneTask, hansTask, pickpocketTask, oakTask,
		killZombieTask, fishAnchoviesTask, breadTask, ironTask, enterHAMTask;

	@Override
	public QuestStep loadStep()
	{
		initializeRequirements();
		setupSteps();

		ConditionalStep doEasy = new ConditionalStep(this, claimReward);

		drayAgiTask = new ConditionalStep(this, drayAgi);
		doEasy.addStep(notDrayAgi, drayAgiTask);

		killZombieTask = new ConditionalStep(this, moveToDraySewer);
		killZombieTask.addStep(inSewer, killZombie);
		doEasy.addStep(notKillZombie, killZombieTask);

		sedridorTask = new ConditionalStep(this, moveToSed);
		sedridorTask.addStep(inMageTower, sedridor);
		doEasy.addStep(notSedridor, sedridorTask);

		enterHAMTask = new ConditionalStep(this, enterHAM);
		doEasy.addStep(notEnterHAM, enterHAMTask);

		killCaveBugTask = new ConditionalStep(this, addRopeToHole);
		killCaveBugTask.addStep(inCave, killCaveBug);
		killCaveBugTask.addStep(addedRopeToHole, moveToDarkHole);
		doEasy.addStep(notKillCaveBug, killCaveBugTask);

		waterRuneTask = new ConditionalStep(this, moveToWaterAltar);
		waterRuneTask.addStep(inWater, waterRune);
		doEasy.addStep(notWaterRune, waterRuneTask);

		breadTask = new ConditionalStep(this, bread);
		doEasy.addStep(notBread, breadTask);

		hansTask = new ConditionalStep(this, hans);
		doEasy.addStep(notHans, hansTask);

		pickpocketTask = new ConditionalStep(this, pickpocket);
		doEasy.addStep(notPickpocket, pickpocketTask);

		oakTask = new ConditionalStep(this, chopOak);
		oakTask.addStep(new Conditions(oakLogs, choppedLogs), burnOak);
		doEasy.addStep(notOak, oakTask);

		ironTask = new ConditionalStep(this, mineIron);
		doEasy.addStep(notIron, ironTask);

		fishAnchoviesTask = new ConditionalStep(this, fishAnchovies);
		doEasy.addStep(notFishAnchovies, fishAnchoviesTask);

		return doEasy;
	}

	@Override
	protected void setupRequirements()
	{
		notDrayAgi = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 1);
		notKillCaveBug = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 2);
		notSedridor = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 3);
		notWaterRune = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 4);
		notHans = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 5);
		notPickpocket = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 6);
		notOak = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 7);
		notKillZombie = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 8);
		notFishAnchovies = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 9);
		notBread = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 10);
		notIron = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 11);
		notEnterHAM = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 12);

		addedRopeToHole = new VarbitRequirement(279, 1);

		lightSource = new ItemRequirement("Light source", ItemCollections.LIGHT_SOURCES).showConditioned(notKillCaveBug).isNotConsumed();
		rope = new ItemRequirement("Rope", ItemID.ROPE).showConditioned(notKillCaveBug);
		spinyHelm = new ItemRequirement("Spiny helmet or slayer helmet (Recommended for low combat levels / Ironmen)",
			ItemCollections.WALL_BEAST).showConditioned(notKillCaveBug).isNotConsumed();
		waterAccessOrAbyss = new ItemRequirement("Access to the Water Altar",
			ItemCollections.WATER_ALTAR).showConditioned(notWaterRune).isNotConsumed();
		waterAccessOrAbyss.setTooltip("Water Talisman/Tiara, Elemental Talisman/Tiara, RC-skill cape or via Abyss");
		runeEss = new ItemRequirement("Essence", ItemCollections.ESSENCE_LOW).showConditioned(notWaterRune);
		dough = new ItemRequirement("Bread dough", ItemID.BREAD_DOUGH).showConditioned(notBread);
		oakLogs = new ItemRequirement("Oak logs", ItemID.OAK_LOGS).showConditioned(notOak);
		tinderbox = new ItemRequirement("Tinderbox", ItemID.TINDERBOX).showConditioned(notOak).isNotConsumed();
		axe = new ItemRequirement("Any axe", ItemCollections.AXES).showConditioned(notOak).isNotConsumed();
		pickaxe = new ItemRequirement("Any pickaxe", ItemCollections.PICKAXES).showConditioned(notIron).isNotConsumed();
		smallFishingNet = new ItemRequirement("Small fishing net", ItemID.NET).showConditioned(notFishAnchovies).isNotConsumed();

		combatGear = new ItemRequirement("Combat gear", -1, -1).isNotConsumed();
		combatGear.setDisplayItemId(BankSlotIcons.getCombatGear());

		food = new ItemRequirement("Food", ItemCollections.GOOD_EATING_FOOD, -1);

		inCave = new ZoneRequirement(cave);
		inSewer = new ZoneRequirement(sewer);
		inMageTower = new ZoneRequirement(mageTower);
		inWater = new ZoneRequirement(water);
		inLumby = new ZoneRequirement(lumby);

		choppedLogs = new ChatMessageRequirement(
			"<col=0040ff>Achievement Diary Stage Task - Current stage: 1.</col>"
		);
		((ChatMessageRequirement) choppedLogs).setInvalidateRequirement(
			new ChatMessageRequirement(
				new Conditions(LogicType.NOR, inLumby),
				"<col=0040ff>Achievement Diary Stage Task - Current stage: 1.</col>"
			)
		);

		runeMysteries = new QuestRequirement(QuestHelperQuest.RUNE_MYSTERIES, QuestState.FINISHED);
		cooksAssistant = new QuestRequirement(QuestHelperQuest.COOKS_ASSISTANT, QuestState.FINISHED);
	}

	@Override
	protected void setupZones()
	{
		lumby = new Zone(new WorldPoint(3212, 3213, 0), new WorldPoint(3227, 3201, 0));
		cave = new Zone(new WorldPoint(3140, 9537, 0), new WorldPoint(3261, 9602, 0));
		sewer = new Zone(new WorldPoint(3077, 9699, 0), new WorldPoint(3132, 9641, 0));
		mageTower = new Zone(new WorldPoint(3095, 9578, 0), new WorldPoint(3122, 9554, 0));
		water = new Zone(new WorldPoint(2688, 4863, 0), new WorldPoint(2751, 4800, 0));
	}

	public void setupSteps()
	{
		drayAgi = new ObjectStep(this, 11404, new WorldPoint(3103, 3279, 0),
			"Complete a lap of the Draynor Rooftop Course.");

		moveToDraySewer = new ObjectStep(this, ObjectID.VAMPIRE_TRAP2, new WorldPoint(3118, 3244, 0),
			"Climb down into the Draynor Sewer.");
		moveToDraySewer.addAlternateObjects(ObjectID.VAMPIRE_TRAP1);
		killZombie = new NpcStep(this, NpcID.ZOMBIE_UNARMED_SEWER1, new WorldPoint(3123, 9648, 0),
			"Kill a zombie.", true);
		killZombie.addAlternateNpcs(NpcID.ZOMBIE_UNARMED_SEWER3, NpcID.ZOMBIE_ARMED_SEWER3, NpcID.ZOMBIE_ARMED_SEWER1, NpcID.ZOMBIE_ARMED_SEWER2);

		moveToSed = new ObjectStep(this, ObjectID.WIZARDS_TOWER_LADDERTOP, new WorldPoint(3104, 3162, 0),
			"Climb down the ladder in the Wizards' Tower.");
		sedridor = new NpcStep(this, NpcID.HEAD_WIZARD_1OP, new WorldPoint(3103, 9571, 0),
			"Teleport to the Rune essence mine via Sedridor.");
		((NpcStep) sedridor).addAlternateNpcs(NpcID.HEAD_WIZARD_2OP);
		sedridor.addDialogStep("Can you teleport me to the Rune Essence?");
		sedridor.addDialogStep("Can you teleport me to the Rune Essence Mine?");

		enterHAM = new ObjectStep(this, ObjectID.OSF_TRAPDOOR_CLOSED, new WorldPoint(3166, 3252, 0),
			"Lock pick and enter the HAM hideout.");
		enterHAM.addAlternateObjects(ObjectID.OSF_TRAPDOOR_OPEN);

		moveToDarkHole = new ObjectStep(this, ObjectID.GOBLIN_CAVE_ENTRANCE, new WorldPoint(3169, 3172, 0),
			"Enter the dark hole in the Lumbridge Swamp.", lightSource, combatGear);
		addRopeToHole = new ObjectStep(this, ObjectID.GOBLIN_CAVE_ENTRANCE, new WorldPoint(3169, 3172, 0),
			"Use a rope on the dark hole in the Lumbridge Swamp, and then enter it.",
			lightSource, rope.highlighted(), combatGear);
		addRopeToHole.addSubSteps(moveToDarkHole);
		addRopeToHole.addIcon(ItemID.ROPE);
		killCaveBug = new NpcStep(this, NpcID.SWAMP_CAVE_BUG, new WorldPoint(3151, 9574, 0),
			"Kill a Cave Bug.", true, combatGear, lightSource);

		moveToWaterAltar = new ObjectStep(this, 34815, new WorldPoint(3185, 3165, 0),
			"Enter the water altar in Lumbridge Swamp.", waterAccessOrAbyss.highlighted(), runeEss);
		moveToWaterAltar.addIcon(ItemID.WATER_TALISMAN);
		waterRune = new ObjectStep(this, ObjectID.WATER_ALTAR, new WorldPoint(2716, 4836, 0),
			"Craft water runes.", runeEss);

		bread = new ObjectStep(this, ObjectID.COOKSQUESTRANGE, new WorldPoint(3212, 3216, 0),
			"Cook bread on the cooking range in Lumbridge Castle.", dough);

		hans = new NpcStep(this, NpcID.HANS, new WorldPoint(3215, 3219, 0),
			"Talk to Hans to learn your age.");
		hans.addDialogStep("Can you tell me how long I've been here?");

		pickpocket = new NpcStep(this, NpcID.MAN2, new WorldPoint(3215, 3219, 0),
			"Pickpocket a man or woman infront of Lumbridge Castle.", true);
		pickpocket.addAlternateNpcs(NpcID.MAN3, NpcID.DSKIN_W_ARDOUNGECITIZEN2, NpcID.AVAN_FITZHARMON_MAN);

		chopOak = new ObjectStep(this, ObjectID.OAKTREE, new WorldPoint(3219, 3206, 0),
			"Chop the oak tree in the Lumbridge Castle Courtyard.", axe);
		burnOak = new ItemStep(this, "Burn the oak logs you've chopped.", tinderbox.highlighted(),
			oakLogs.highlighted());

		mineIron = new ObjectStep(this, ObjectID.IRONROCK1, new WorldPoint(3303, 3284, 0),
			"Mine some iron ore at the Al-Kharid mine.", pickaxe);

		fishAnchovies = new NpcStep(this, NpcID._0_51_49_SALTFISH, new WorldPoint(3267, 3148, 0),
			"Fish for anchovies in Al-Kharid.", smallFishingNet);

		claimReward = new NpcStep(this, NpcID.HATIUS_LUMBRIDGE_DIARY, new WorldPoint(3235, 3213, 0),
			"Talk to Hatius Cosaintus in Lumbridge to claim your reward!");
		claimReward.addDialogStep("I have a question about my Achievement Diary.");
	}

	@Override
	public List<ItemRequirement> getItemRequirements()
	{
		return Arrays.asList(lightSource, runeEss, axe, tinderbox, smallFishingNet, pickaxe,
			waterAccessOrAbyss, dough, combatGear);
	}

	@Override
	public List<ItemRequirement> getItemRecommended()
	{
		return Arrays.asList(food, rope, spinyHelm);
	}

	@Override
	public List<Requirement> getGeneralRequirements()
	{
		List<Requirement> reqs = new ArrayList<>();
		reqs.add(new SkillRequirement(Skill.AGILITY, 10, true));
		reqs.add(new SkillRequirement(Skill.FIREMAKING, 15, true));
		reqs.add(new SkillRequirement(Skill.FISHING, 15, true));
		reqs.add(new SkillRequirement(Skill.MINING, 15, true));
		reqs.add(new SkillRequirement(Skill.RUNECRAFT, 5, true));
		reqs.add(new SkillRequirement(Skill.SLAYER, 7, true));
		reqs.add(new SkillRequirement(Skill.WOODCUTTING, 15, true));

		reqs.add(runeMysteries);
		reqs.add(cooksAssistant);

		return reqs;
	}

	@Override
	public List<String> getCombatRequirements()
	{
		return Collections.singletonList("Zombie (lvl 13) and cave bug (lvl 6)");
	}

	@Override
	public List<ItemReward> getItemRewards()
	{
		return Arrays.asList(
			new ItemReward("Explorer's ring 1", ItemID.LUMBRIDGE_RING_EASY),
			new ItemReward("2,500 Exp. Lamp (Any skill over 30)", ItemID.THOSF_REWARD_LAMP)
		);
	}

	@Override
	public List<UnlockReward> getUnlockRewards()
	{
		return Arrays.asList(
			new UnlockReward("30 casts of Low Level Alchemy per day without runes (does not provide experience) from Explorer's ring"),
			new UnlockReward("50% run energy replenish twice a day from Explorer's ring")
		);
	}

	@Override
	public List<PanelDetails> getPanels()
	{
		List<PanelDetails> allSteps = new ArrayList<>();

		PanelDetails draynorRooftopsSteps = new PanelDetails("Draynor Rooftops", Collections.singletonList(drayAgi),
			new SkillRequirement(Skill.AGILITY, 10, true));
		draynorRooftopsSteps.setDisplayCondition(notDrayAgi);
		draynorRooftopsSteps.setLockingStep(drayAgiTask);
		allSteps.add(draynorRooftopsSteps);

		PanelDetails zombieSteps = new PanelDetails("Kill Zombie in Draynor Sewers", Arrays.asList(moveToDraySewer,
			killZombie), combatGear);
		zombieSteps.setDisplayCondition(notKillZombie);
		zombieSteps.setLockingStep(killZombieTask);
		allSteps.add(zombieSteps);

		PanelDetails sedridorSteps = new PanelDetails("Rune Essence Mine", Arrays.asList(moveToSed, sedridor),
			runeMysteries);
		sedridorSteps.setDisplayCondition(notSedridor);
		sedridorSteps.setLockingStep(sedridorTask);
		allSteps.add(sedridorSteps);

		PanelDetails enterTheHamHideoutSteps = new PanelDetails("Enter the HAM Hideout", Collections.singletonList(enterHAM));
		enterTheHamHideoutSteps.setDisplayCondition(notEnterHAM);
		enterTheHamHideoutSteps.setLockingStep(enterHAMTask);
		allSteps.add(enterTheHamHideoutSteps);

		PanelDetails killCaveBugSteps = new PanelDetails("Kill Cave Bug", Arrays.asList(addRopeToHole, killCaveBug),
			new SkillRequirement(Skill.SLAYER, 7, true), lightSource, rope, spinyHelm);
		killCaveBugSteps.setDisplayCondition(notKillCaveBug);
		killCaveBugSteps.setLockingStep(killCaveBugTask);
		allSteps.add(killCaveBugSteps);

		PanelDetails waterRunesSteps = new PanelDetails("Craft Water Runes", Arrays.asList(moveToWaterAltar, waterRune),
			new SkillRequirement(Skill.RUNECRAFT, 5, true), waterAccessOrAbyss, runeEss);
		waterRunesSteps.setDisplayCondition(notWaterRune);
		waterRunesSteps.setLockingStep(waterRuneTask);
		allSteps.add(waterRunesSteps);

		PanelDetails breadSteps = new PanelDetails("Cooking Bread", Collections.singletonList(bread), cooksAssistant,
			dough);
		breadSteps.setDisplayCondition(notBread);
		breadSteps.setLockingStep(breadTask);
		allSteps.add(breadSteps);

		PanelDetails hansSteps = new PanelDetails("Learn Age from Hans", Collections.singletonList(hans));
		hansSteps.setDisplayCondition(notHans);
		hansSteps.setLockingStep(hansTask);
		allSteps.add(hansSteps);

		PanelDetails pickpocketSteps = new PanelDetails("Pickpocket Man or Woman", Collections.singletonList(pickpocket));
		pickpocketSteps.setDisplayCondition(notPickpocket);
		pickpocketSteps.setLockingStep(pickpocketTask);
		allSteps.add(pickpocketSteps);

		PanelDetails oakSteps = new PanelDetails("Chop and Burn Oak Logs", Arrays.asList(chopOak, burnOak),
			new SkillRequirement(Skill.WOODCUTTING, 15, true), new SkillRequirement(Skill.FIREMAKING, 15, true), tinderbox, axe);
		oakSteps.setDisplayCondition(notOak);
		oakSteps.setLockingStep(oakTask);
		allSteps.add(oakSteps);

		PanelDetails mineIronSteps = new PanelDetails("Mine Iron in Al-Kharid", Collections.singletonList(mineIron),
			new SkillRequirement(Skill.MINING, 15, true), pickaxe);
		mineIronSteps.setDisplayCondition(notIron);
		mineIronSteps.setLockingStep(ironTask);
		allSteps.add(mineIronSteps);

		PanelDetails anchoviesSteps = new PanelDetails("Fish Anchovies in Al-Kharid",
			Collections.singletonList(fishAnchovies), new SkillRequirement(Skill.FISHING, 15, true), smallFishingNet);
		anchoviesSteps.setDisplayCondition(notFishAnchovies);
		anchoviesSteps.setLockingStep(fishAnchoviesTask);
		allSteps.add(anchoviesSteps);

		allSteps.add(new PanelDetails("Finishing off", Collections.singletonList(claimReward)));

		return allSteps;
	}
}
