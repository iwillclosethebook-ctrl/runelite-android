package net.runelite.client.plugins.autoagility;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;

/**
 * Course data, taken from Microbot's agility plugin (VarrockCourse, CanafisCourse,
 * SeersCourse): start tile, required level and the obstacle list in order. An obstacle
 * with a position rule only counts while the player's x / y pass it (same semantics as
 * Microbot's {@code Operation.check(playerCoord, required)}; -1 with GREATER = always).
 */
public enum AgilityCourse
{
        VARROCK("Varrock Rooftop", new WorldPoint(3221, 3414, 0), 30,
                ob(ObjectID.ROOFTOPS_VARROCK_WALLCLIMB),
                ob(ObjectID.ROOFTOPS_VARROCK_CLOTHESLINE),
                ob(ObjectID.ROOFTOPS_VARROCK_LEAPTORUINS),
                ob(ObjectID.ROOFTOPS_VARROCK_WALLSWING),
                ob(ObjectID.ROOFTOPS_VARROCK_WALLSCRAMBLE, -1, 3402, Op.GREATER, Op.GREATER_EQUAL),
                ob(ObjectID.ROOFTOPS_VARROCK_LEAPTOBALCONY),
                ob(ObjectID.ROOFTOPS_VARROCK_LEAPDOWN, -1, 3402, Op.GREATER, Op.LESS_EQUAL),
                ob(ObjectID.ROOFTOPS_VARROCK_STEPUPROOF, -1, 3408, Op.GREATER, Op.LESS_EQUAL),
                ob(ObjectID.ROOFTOPS_VARROCK_FINISH)),
        CANIFIS("Canifis Rooftop", new WorldPoint(3507, 3489, 0), 40,
                ob(ObjectID.ROOFTOPS_CANIFIS_START_TREE),
                ob(ObjectID.ROOFTOPS_CANIFIS_JUMP),
                ob(ObjectID.ROOFTOPS_CANIFIS_JUMP_2, 3498, -1, Op.GREATER_EQUAL, Op.GREATER),
                ob(ObjectID.ROOFTOPS_CANIFIS_JUMP_5),
                ob(ObjectID.ROOFTOPS_CANIFIS_JUMP_3),
                ob(ObjectID.ROOFTOPS_CANIFIS_POLEVAULT),
                ob(ObjectID.ROOFTOPS_CANIFIS_JUMP_4),
                ob(ObjectID.ROOFTOPS_CANIFIS_LEAPDOWN)),
        SEERS("Seers' Village Rooftop", new WorldPoint(2729, 3486, 0), 60,
                ob(ObjectID.ROOFTOPS_SEERS_WALLCLIMB),
                ob(ObjectID.ROOFTOPS_SEERS_JUMP),
                ob(ObjectID.ROOFTOPS_SEERS_TIGHTROPE),
                ob(ObjectID.ROOFTOPS_SEERS_JUMP_1),
                ob(ObjectID.ROOFTOPS_SEERS_JUMP_2),
                ob(ObjectID.ROOFTOPS_SEERS_LEAPDOWN));

        enum Op
        {
                GREATER, LESS, LESS_EQUAL, EQUAL, GREATER_EQUAL;

                boolean check(int value, int against)
                {
                        switch (this)
                        {
                                case GREATER:
                                        return value > against;
                                case LESS:
                                        return value < against;
                                case LESS_EQUAL:
                                        return value <= against;
                                case EQUAL:
                                        return value == against;
                                case GREATER_EQUAL:
                                default:
                                        return value >= against;
                        }
                }
        }

        static final class Obstacle
        {
                final int objectId;
                final int requiredX;
                final int requiredY;
                final Op opX;
                final Op opY;

                Obstacle(int objectId, int requiredX, int requiredY, Op opX, Op opY)
                {
                        this.objectId = objectId;
                        this.requiredX = requiredX;
                        this.requiredY = requiredY;
                        this.opX = opX;
                        this.opY = opY;
                }

                /** Microbot's filter: does the player's position allow this obstacle now? */
                boolean allows(WorldPoint p)
                {
                        return opX.check(p.getX(), requiredX) && opY.check(p.getY(), requiredY);
                }
        }

        private static Obstacle ob(int id)
        {
                return new Obstacle(id, -1, -1, Op.GREATER, Op.GREATER);
        }

        private static Obstacle ob(int id, int x, int y, Op opX, Op opY)
        {
                return new Obstacle(id, x, y, opX, opY);
        }

        private final String label;
        final WorldPoint start;
        final int requiredLevel;
        final List<Obstacle> obstacles;

        AgilityCourse(String label, WorldPoint start, int requiredLevel, Obstacle... obstacles)
        {
                this.label = label;
                this.start = start;
                this.requiredLevel = requiredLevel;
                this.obstacles = Collections.unmodifiableList(Arrays.asList(obstacles));
        }

        @Override
        public String toString()
        {
                return label;
        }
}
