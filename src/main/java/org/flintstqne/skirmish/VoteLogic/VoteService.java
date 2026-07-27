package org.flintstqne.skirmish.VoteLogic;

import org.bukkit.entity.Player;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * End-of-round gamemode vote (design doc §7.9). Votes are ephemeral — cleared at the
 * start of every end-round sequence and never written to disk (§5.2).
 */
public final class VoteService {

    private final Map<UUID, GamemodeType> votes = new HashMap<>();
    private final Random random = new Random();
    private final List<GamemodeType> enabled;
    private final List<GamemodeType> playable;

    /**
     * @param enabled  {@code vote.enabled-gamemodes} from config
     * @param playable modes with working round logic — the vote can't offer what can't be played yet
     */
    public VoteService(List<GamemodeType> enabled, List<GamemodeType> playable) {
        this.enabled = List.copyOf(enabled);
        this.playable = List.copyOf(playable);
    }

    /** Configured voteable modes, minus anything not yet implemented. Never empty. */
    public List<GamemodeType> getOptions() {
        List<GamemodeType> options = new ArrayList<>(enabled);
        options.retainAll(playable);
        if (options.isEmpty()) options.add(GamemodeType.TDM);
        return options;
    }

    public void reset() {
        votes.clear();
    }

    /** Votes are changeable until the countdown ends — a second vote replaces the first. */
    public void vote(UUID voter, GamemodeType mode) {
        if (!getOptions().contains(mode)) return;
        votes.put(voter, mode);
    }

    public void vote(Player player, GamemodeType mode) {
        vote(player.getUniqueId(), mode);
    }

    public GamemodeType getVote(UUID voter) {
        return votes.get(voter);
    }

    public GamemodeType getVote(Player player) {
        return getVote(player.getUniqueId());
    }

    public int getVoteCount(GamemodeType mode) {
        int count = 0;
        for (GamemodeType vote : votes.values()) if (vote == mode) count++;
        return count;
    }

    public int getTotalVotes() {
        return votes.size();
    }

    public Map<GamemodeType, Integer> getTally() {
        Map<GamemodeType, Integer> tally = new EnumMap<>(GamemodeType.class);
        for (GamemodeType mode : getOptions()) tally.put(mode, getVoteCount(mode));
        return tally;
    }

    /** Percentage for the GUI bars; 0 when nobody has voted rather than a divide by zero. */
    public int getPercentage(GamemodeType mode) {
        int total = getTotalVotes();
        return total == 0 ? 0 : Math.round(getVoteCount(mode) * 100f / total);
    }

    /** Highest vote count wins; ties and an empty tally both break randomly. */
    public GamemodeType getWinner() {
        List<GamemodeType> options = getOptions();
        int best = -1;
        List<GamemodeType> leaders = new ArrayList<>();
        for (GamemodeType mode : options) {
            int count = getVoteCount(mode);
            if (count > best) {
                best = count;
                leaders.clear();
                leaders.add(mode);
            } else if (count == best) {
                leaders.add(mode);
            }
        }
        return leaders.get(random.nextInt(leaders.size()));
    }
}
