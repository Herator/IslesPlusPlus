package com.islesplusplus.map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// party members off the sidebar "P » name" rows
public final class Party {
    // lowercase
    public static volatile Set<String> members = Set.of();

    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern COLOUR = Pattern.compile("§.?");

    private Party() {}

    public static void tick(MinecraftClient client) {
        if (client.world == null) { members = Set.of(); return; }
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective sidebar = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (sidebar == null) { members = Set.of(); return; }
        Set<String> party = new HashSet<>();
        for (ScoreHolder holder : sb.getKnownScoreHolders()) {
            if (sb.getScore(holder, sidebar) == null) continue;
            String name = holder.getNameForScoreboard();
            Team team = sb.getScoreHolderTeam(name);
            String line = COLOUR.matcher((team != null ? team.getPrefix().getString() : "") + name
                + (team != null ? team.getSuffix().getString() : "")).replaceAll("").trim();
            if (!line.startsWith("P")) continue;
            String member = memberName(line);
            if (member != null) party.add(member.toLowerCase(Locale.ROOT));
        }
        members = Set.copyOf(party);
    }

    /** The member's name off a party row ("P » [unknown player head] chrrisk ❤126" -> "chrrisk"),
     * or null. It's the first word after the » that could be a Minecraft name, skipping [bracketed] bits. */
    static String memberName(String line) {
        if (!line.matches("^P\\s*».*")) return null;
        int at = line.indexOf('»');
        for (String word : BRACKETED.matcher(line.substring(at + 1)).replaceAll(" ").trim().split("\\s+")) {
            if (USERNAME.matcher(word).matches()) return word;
        }
        return null;
    }
}
