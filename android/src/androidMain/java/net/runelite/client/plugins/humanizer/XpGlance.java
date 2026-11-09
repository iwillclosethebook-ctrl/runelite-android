package net.runelite.client.plugins.humanizer;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;

/**
 * The "how much XP do I have now?" habit. Every few minutes (a random time between the
 * configured min and max), while the script is just waiting, the player opens the Skills
 * tab (with its F-key when one is bound, otherwise a tap), rests a finger on the skill
 * being trained for a moment to read the tooltip, then goes back to the inventory.
 *
 * <p>Call {@link #tick} once per game tick from any script. It only acts while
 * {@code idle} is true and the input is free, and gives up quietly (going back to the
 * inventory) if the script needs to act in the middle of a glance.
 */
public final class XpGlance
{
        private enum Step
        {
                WAIT, OPEN_SKILLS, LOOK, BACK
        }

        private final HumanInput input;
        private Step step = Step.WAIT;
        private long nextAt;
        private int stepTicks;

        public XpGlance(HumanInput input)
        {
                this.input = input;
                schedule();
        }

        /** Restart the timer (script started or stopped). */
        public void reset()
        {
                step = Step.WAIT;
                schedule();
        }

        /** "next in 3m 12s" / "checking XP" — for overlays. */
        public String status()
        {
                if (step != Step.WAIT)
                {
                        return "checking XP";
                }
                long s = Math.max(0, (nextAt - System.currentTimeMillis()) / 1000);
                return "next in " + (s / 60) + "m " + (s % 60) + "s";
        }

        /** True while a glance is under way (the script should leave the inventory alone). */
        public boolean active()
        {
                return step != Step.WAIT;
        }

        /**
         * @param skill the skill being trained (null = just look at the total level)
         * @param idle  true when the script is waiting (fishing, chopping, mid-fight with nothing to do)
         */
        public void tick(Client client, Skill skill, boolean idle)
        {
                trackGains(client);
                if (skill == null)
                {
                        skill = lastGained;
                }
                HumanizerConfig cfg = input.config();
                if (!cfg.xpChecks())
                {
                        step = Step.WAIT;
                        return;
                }
                if (step == Step.WAIT)
                {
                        if (!idle || input.isBusy() || System.currentTimeMillis() < nextAt)
                        {
                                return;
                        }
                        step = Step.OPEN_SKILLS;
                        stepTicks = 0;
                }
                stepTicks++;
                if (!idle && step != Step.BACK)
                {
                        // Something to do: abandon the glance and get the inventory back.
                        step = Step.BACK;
                        stepTicks = 0;
                }
                if (input.isBusy())
                {
                        if (stepTicks > 20)
                        {
                                finish();
                        }
                        return;
                }
                switch (step)
                {
                        case OPEN_SKILLS:
                                if (GameHelpers.openTab(client) == GameHelpers.TAB_SKILLS)
                                {
                                        step = Step.LOOK;
                                        stepTicks = 0;
                                }
                                else if (stepTicks > 8 || !GameHelpers.openTab(client, input, GameHelpers.TAB_SKILLS))
                                {
                                        step = Step.BACK;
                                        stepTicks = 0;
                                }
                                break;
                        case LOOK:
                        {
                                Widget w = skillWidget(client, skill);
                                int[] p = w == null ? null : input.widgetPoint(w);
                                if (p != null)
                                {
                                        // Reading time rides a sine of a random phase: 1.2s .. 3.6s.
                                        double ph = input.random().nextDouble() * 2.0d * Math.PI;
                                        int dwell = 1200 + (int) Math.round(1200.0d * (1.0d + Math.sin(ph))) + input.random().nextInt(300);
                                        input.hover(p, dwell, "xp " + (skill == null ? "total" : skill.getName()));
                                }
                                step = Step.BACK;
                                stepTicks = 0;
                                break;
                        }
                        case BACK:
                                if (GameHelpers.inventoryTabOpen(client) || stepTicks > 10)
                                {
                                        finish();
                                }
                                else
                                {
                                        GameHelpers.openInventoryTab(client, input);
                                }
                                break;
                        default:
                                break;
                }
        }

        private final int[] lastXp = new int[Skill.values().length];
        private boolean xpSeeded;
        private Skill lastGained;

        /** Remembers which skill gained XP last (what a player would look at). */
        private void trackGains(Client client)
        {
                Skill[] skills = Skill.values();
                for (int i = 0; i < skills.length; i++)
                {
                        int xp;
                        try
                        {
                                xp = client.getSkillExperience(skills[i]);
                        }
                        catch (RuntimeException e)
                        {
                                continue;
                        }
                        if (xpSeeded && xp > lastXp[i] && lastXp[i] > 0 && skills[i] != Skill.HITPOINTS)
                        {
                                lastGained = skills[i];
                        }
                        else if (xpSeeded && xp > lastXp[i] && lastGained == null)
                        {
                                lastGained = skills[i];
                        }
                        lastXp[i] = xp;
                }
                xpSeeded = true;
        }

        private void finish()
        {
                step = Step.WAIT;
                schedule();
        }

        private void schedule()
        {
                HumanizerConfig cfg = input.config();
                int min = Math.max(1, cfg.xpCheckMinMin());
                int max = Math.max(min, cfg.xpCheckMaxMin());
                long spanMs = (max - min) * 60_000L;
                // Skewed toward the short end: square of a uniform number.
                double u = input.random().nextDouble();
                nextAt = System.currentTimeMillis() + min * 60_000L + (long) (spanMs * u * u) + input.random().nextInt(20_000);
        }

        /** The Skills-tab widget for a skill (InterfaceID.Stats.<NAME>), or the total-level box. */
        static Widget skillWidget(Client client, Skill skill)
        {
                int id = net.runelite.api.gameval.InterfaceID.Stats.TOTAL;
                if (skill != null)
                {
                        try
                        {
                                id = net.runelite.api.gameval.InterfaceID.Stats.class.getField(skill.name()).getInt(null);
                        }
                        catch (ReflectiveOperationException ignored)
                        {
                                // unknown skill: look at the total instead
                        }
                }
                Widget w = client.getWidget(id);
                return w == null || w.isHidden() ? null : w;
        }
}
