package net.runelite.client.plugins.gamedata;

import net.runelite.api.Client;

/**
 * Which bank a script should use, as a settings dropdown. Built from {@link BankLocation}
 * (the shared bank list taken from Microbot), plus two choices that are not a bank:
 * "Same as Humanizer default" (the one bank all scripts share, set once in the
 * Humanizer settings) and "Closest bank".
 */
public enum BankPick
{
        SHARED("Same as Humanizer default", null),
        CLOSEST("Closest bank", null),
        AL_KHARID("Al kharid", BankLocation.AL_KHARID),
        ALDARIN("Aldarin", BankLocation.ALDARIN),
        ARCEUUS("Arceuus", BankLocation.ARCEUUS),
        ARDOUGNE_NORTH("Ardougne north", BankLocation.ARDOUGNE_NORTH),
        ARDOUGNE_SOUTH("Ardougne south", BankLocation.ARDOUGNE_SOUTH),
        AUBURNVALE("Auburnvale", BankLocation.AUBURNVALE),
        BARBARIAN_OUTPOST("Barbarian outpost", BankLocation.BARBARIAN_OUTPOST),
        BLAST_FURNACE_BANK("Blast furnace bank", BankLocation.BLAST_FURNACE_BANK),
        BLAST_MINE("Blast mine", BankLocation.BLAST_MINE),
        BURGH_DE_ROTT("Burgh de rott", BankLocation.BURGH_DE_ROTT),
        CAM_TORUM("Cam torum", BankLocation.CAM_TORUM),
        CAMELOT("Camelot", BankLocation.CAMELOT),
        CAMODZAAL("Camodzaal", BankLocation.CAMODZAAL),
        CANIFIS("Canifis", BankLocation.CANIFIS),
        CASTLE_WARS("Castle wars", BankLocation.CASTLE_WARS),
        CATHERBY("Catherby", BankLocation.CATHERBY),
        CLAN_HALL("Clan hall", BankLocation.CLAN_HALL),
        COOKS_GUILD("Cooks guild", BankLocation.COOKS_GUILD),
        CORSAIR_COVE("Corsair cove", BankLocation.CORSAIR_COVE),
        CRAFTING_GUILD("Crafting guild", BankLocation.CRAFTING_GUILD),
        DARKFROST("Darkfrost", BankLocation.DARKFROST),
        DARKMEYER("Darkmeyer", BankLocation.DARKMEYER),
        DEEPFIN_MINE_EAST("Deepfin mine east", BankLocation.DEEPFIN_MINE_EAST),
        DEEPFIN_MINE_MID("Deepfin mine mid", BankLocation.DEEPFIN_MINE_MID),
        DEEPFIN_POINT("Deepfin point", BankLocation.DEEPFIN_POINT),
        DORGESH_KAAN_BANK("Dorgesh kaan bank", BankLocation.DORGESH_KAAN_BANK),
        DRAYNOR_VILLAGE("Draynor village", BankLocation.DRAYNOR_VILLAGE),
        DUEL_ARENA("Duel arena", BankLocation.DUEL_ARENA),
        DWARF_MINE_BANK("Dwarf mine bank", BankLocation.DWARF_MINE_BANK),
        EDGEVILLE("Edgeville", BankLocation.EDGEVILLE),
        FALADOR_EAST("Falador east", BankLocation.FALADOR_EAST),
        FALADOR_WEST("Falador west", BankLocation.FALADOR_WEST),
        FARMING_GUILD("Farming guild", BankLocation.FARMING_GUILD),
        FEROX_ENCLAVE("Ferox enclave", BankLocation.FEROX_ENCLAVE),
        FISHING_GUILD("Fishing guild", BankLocation.FISHING_GUILD),
        FOSSIL_ISLAND("Fossil island", BankLocation.FOSSIL_ISLAND),
        FOSSIL_ISLAND_WRECK("Fossil island wreck", BankLocation.FOSSIL_ISLAND_WRECK),
        GNOME_BANK("Gnome bank", BankLocation.GNOME_BANK),
        GNOME_TREE_BANK_SOUTH("Gnome tree bank south", BankLocation.GNOME_TREE_BANK_SOUTH),
        GNOME_TREE_BANK_WEST("Gnome tree bank west", BankLocation.GNOME_TREE_BANK_WEST),
        GRAND_EXCHANGE("Grand exchange", BankLocation.GRAND_EXCHANGE),
        GREAT_KOUREND_CASTLE("Great kourend castle", BankLocation.GREAT_KOUREND_CASTLE),
        GUARDIANS_OF_THE_RIFT("Guardians of the rift", BankLocation.GUARDIANS_OF_THE_RIFT),
        HALLOWED_SEPULCHRE("Hallowed sepulchre", BankLocation.HALLOWED_SEPULCHRE),
        HOSIDIUS("Hosidius", BankLocation.HOSIDIUS),
        HOSIDIUS_KITCHEN("Hosidius kitchen", BankLocation.HOSIDIUS_KITCHEN),
        HUNTERS_GUILD("Hunters guild", BankLocation.HUNTERS_GUILD),
        ISLE_OF_SOULS("Isle of souls", BankLocation.ISLE_OF_SOULS),
        JATIZSO("Jatizso", BankLocation.JATIZSO),
        LANDS_END("Lands end", BankLocation.LANDS_END),
        LEGENDS_GUILD("Legends guild", BankLocation.LEGENDS_GUILD),
        LLETYA("Lletya", BankLocation.LLETYA),
        LOVAKENGJ("Lovakengj", BankLocation.LOVAKENGJ),
        LUMBRIDGE_BASEMENT("Lumbridge basement", BankLocation.LUMBRIDGE_BASEMENT),
        LUMBRIDGE_FRONT("Lumbridge front", BankLocation.LUMBRIDGE_FRONT),
        LUMBRIDGE_TOP("Lumbridge top", BankLocation.LUMBRIDGE_TOP),
        LUNAR_ISLE("Lunar isle", BankLocation.LUNAR_ISLE),
        MAGE_ARENA("Mage arena", BankLocation.MAGE_ARENA),
        MAGE_TRAINING_ARENA("Mage training arena", BankLocation.MAGE_TRAINING_ARENA),
        MINING_GUILD("Mining guild", BankLocation.MINING_GUILD),
        MISTROCK("Mistrock", BankLocation.MISTROCK),
        MOR_UL_REK("Mor ul rek", BankLocation.MOR_UL_REK),
        MOTHERLOAD("Motherload", BankLocation.MOTHERLOAD),
        MOUNT_KARUULM("Mount karuulm", BankLocation.MOUNT_KARUULM),
        MOUNT_QUIDAMORTEM("Mount quidamortem", BankLocation.MOUNT_QUIDAMORTEM),
        MYTHS_GUILD("Myths guild", BankLocation.MYTHS_GUILD),
        NARDAH("Nardah", BankLocation.NARDAH),
        NEITIZNOT("Neitiznot", BankLocation.NEITIZNOT),
        NEMUS_RETREAT("Nemus retreat", BankLocation.NEMUS_RETREAT),
        PEST_CONTROL("Pest control", BankLocation.PEST_CONTROL),
        PISCARILIUS("Piscarilius", BankLocation.PISCARILIUS),
        PISCATORIS_FISHING_COLONY("Piscatoris fishing colony", BankLocation.PISCATORIS_FISHING_COLONY),
        PORT_KHAZARD("Port khazard", BankLocation.PORT_KHAZARD),
        PORT_PHASMATYS("Port phasmatys", BankLocation.PORT_PHASMATYS),
        PRIFDDINAS_NORTH("Prifddinas north", BankLocation.PRIFDDINAS_NORTH),
        PRIFDDINAS_SOUTH("Prifddinas south", BankLocation.PRIFDDINAS_SOUTH),
        ROGUES_DEN_CHEST("Rogues den chest", BankLocation.ROGUES_DEN_CHEST),
        ROGUES_DEN_EMERALD_BENEDICT("Rogues den emerald benedict", BankLocation.ROGUES_DEN_EMERALD_BENEDICT),
        RUINS_OF_UNKAH("Ruins of unkah", BankLocation.RUINS_OF_UNKAH),
        SHANTY_PASS("Shanty pass", BankLocation.SHANTY_PASS),
        SHAYZIEN_BANK("Shayzien bank", BankLocation.SHAYZIEN_BANK),
        SHAYZIEN_CHEST("Shayzien chest", BankLocation.SHAYZIEN_CHEST),
        SHILO_VILLAGE("Shilo village", BankLocation.SHILO_VILLAGE),
        SOPHANEM("Sophanem", BankLocation.SOPHANEM),
        SULPHUR_MINE("Sulphur mine", BankLocation.SULPHUR_MINE),
        SUNBLEAK_ISLAND("Sunbleak island", BankLocation.SUNBLEAK_ISLAND),
        TAL_TEKLAN("Tal teklan", BankLocation.TAL_TEKLAN),
        TREE_GNOME_STRONGHOLD_NIEVE("Tree gnome stronghold nieve", BankLocation.TREE_GNOME_STRONGHOLD_NIEVE),
        TZHAAR("Tzhaar", BankLocation.TZHAAR),
        VARLAMORE_EAST("Varlamore east", BankLocation.VARLAMORE_EAST),
        VARLAMORE_WEST("Varlamore west", BankLocation.VARLAMORE_WEST),
        VARROCK_EAST("Varrock east", BankLocation.VARROCK_EAST),
        VARROCK_WEST("Varrock west", BankLocation.VARROCK_WEST),
        VINERY_BANK("Vinery bank", BankLocation.VINERY_BANK),
        VOLCANO_BANK("Volcano bank", BankLocation.VOLCANO_BANK),
        WARRIORS_GUILD("Warriors guild", BankLocation.WARRIORS_GUILD),
        WINTERTODT("Wintertodt", BankLocation.WINTERTODT),
        WOODCUTTING_GUILD("Woodcutting guild", BankLocation.WOODCUTTING_GUILD),
        YANILLE("Yanille", BankLocation.YANILLE),
        ZANARIS("Zanaris", BankLocation.ZANARIS),
        ZEAH_SAND_BANK("Zeah sand bank", BankLocation.ZEAH_SAND_BANK),
        ;

        private final String label;
        private final BankLocation bank;

        BankPick(String label, BankLocation bank)
        {
                this.label = label;
                this.bank = bank;
        }

        /** The bank this choice names, or null for "closest" / "shared". */
        public BankLocation bank()
        {
                return bank;
        }

        /**
         * The bank to use: the script's own pick, else the shared Humanizer default when
         * the script says "shared", else null (= go to the closest usable bank).
         */
        public static BankLocation resolve(BankPick scriptPick, BankPick sharedDefault)
        {
                BankPick p = scriptPick == null ? SHARED : scriptPick;
                if (p == SHARED)
                {
                        p = sharedDefault == null ? CLOSEST : sharedDefault;
                }
                return p.bank;
        }

        /** Why the chosen bank cannot be used right now, or null when it can. Client thread. */
        public static String whyNot(Client client, BankLocation b)
        {
                if (b == null)
                {
                        return null;
                }
                if (b.isMembers() && (client.getWorldType() == null || !client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS)))
                {
                        return b.getDisplayName() + " is members only";
                }
                for (BankLocation.Requirement r : b.getRequirements())
                {
                        if (!r.isMet(client))
                        {
                                return b.getDisplayName() + " needs " + r;
                        }
                }
                return null;
        }

        @Override
        public String toString()
        {
                return label;
        }
}
