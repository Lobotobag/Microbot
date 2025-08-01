/*
 * Copyright (c) 2020, Zoinkwiz <https://github.com/Zoinkwiz>
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
package net.runelite.client.plugins.microbot.questhelper.helpers.quests.coldwar;

import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.steps.WidgetStep;
import net.runelite.client.plugins.microbot.questhelper.steps.widget.WidgetDetails;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;

import java.util.Collections;

public class PenguinEmote extends WidgetStep
{
	public PenguinEmote(QuestHelper questHelper)
	{
		super(questHelper, "Perform the 3 emotes the penguins performed in the bird hide cutscene.");
	}

	@Override
	public void startUp()
	{
		super.startUp();
		updateWidgets();
	}

	@Override
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		super.onVarbitChanged(varbitChanged);
		updateWidgets();
	}

	public void updateWidgets()
	{
		int currentEmoteStep = client.getVarbitValue(VarbitID.PENG_EMOTE_CHECK);
		int currentEmoteID = 0;
		if (currentEmoteStep == 0)
		{
			currentEmoteID = 8 + client.getVarbitValue(VarbitID.PENG_EMOTE_1);
		}
		else if (currentEmoteStep == 1)
		{
			currentEmoteID = 8 + client.getVarbitValue(VarbitID.PENG_EMOTE_2);
		}
		else if (currentEmoteStep == 2)
		{
			currentEmoteID = 8 + client.getVarbitValue(VarbitID.PENG_EMOTE_3);
		}
		this.setWidgetDetails(Collections.singletonList(new WidgetDetails(223, currentEmoteID, -1)));
	}
}

