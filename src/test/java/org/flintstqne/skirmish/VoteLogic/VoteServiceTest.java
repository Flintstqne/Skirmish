package org.flintstqne.skirmish.VoteLogic;

import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-of-round vote tallying (design doc §7.9, §9.4). */
class VoteServiceTest {

    private static final List<GamemodeType> ALL = List.of(GamemodeType.KOTH, GamemodeType.DOMINATION,
            GamemodeType.TDM, GamemodeType.FFA, GamemodeType.GUN_GAME);

    private VoteService service(List<GamemodeType> enabled, List<GamemodeType> playable) {
        return new VoteService(enabled, playable);
    }

    @Test
    void optionsAreConfiguredModesIntersectedWithPlayableOnes() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM, GamemodeType.FFA));
        assertEquals(List.of(GamemodeType.TDM, GamemodeType.FFA), votes.getOptions());
    }

    @Test
    void configDisablingAModeRemovesItEvenIfPlayable() {
        VoteService votes = service(List.of(GamemodeType.TDM), List.of(GamemodeType.TDM, GamemodeType.FFA));
        assertEquals(List.of(GamemodeType.TDM), votes.getOptions());
    }

    @Test
    void emptyIntersectionFallsBackToTdmRatherThanNoOptions() {
        // Config could disable everything that is currently implemented - the round loop
        // still has to be able to start something.
        VoteService votes = service(List.of(GamemodeType.KOTH), List.of(GamemodeType.TDM));
        assertEquals(List.of(GamemodeType.TDM), votes.getOptions());
    }

    @Test
    void votingForAnUnofferedModeIsIgnored() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM));
        UUID voter = UUID.randomUUID();
        votes.vote(voter, GamemodeType.KOTH);
        assertNull(votes.getVote(voter));
        assertEquals(0, votes.getTotalVotes());
    }

    @Test
    void secondVoteReplacesTheFirst() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM, GamemodeType.FFA));
        UUID voter = UUID.randomUUID();
        votes.vote(voter, GamemodeType.TDM);
        votes.vote(voter, GamemodeType.FFA);

        assertEquals(GamemodeType.FFA, votes.getVote(voter));
        assertEquals(1, votes.getTotalVotes(), "changing a vote must not add a second one");
        assertEquals(0, votes.getVoteCount(GamemodeType.TDM));
    }

    @Test
    void percentagesAreOutOfVotesCastAndSurviveAnEmptyTally() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM, GamemodeType.FFA));
        assertEquals(0, votes.getPercentage(GamemodeType.TDM), "no votes must not divide by zero");

        votes.vote(UUID.randomUUID(), GamemodeType.TDM);
        votes.vote(UUID.randomUUID(), GamemodeType.TDM);
        votes.vote(UUID.randomUUID(), GamemodeType.FFA);

        assertEquals(67, votes.getPercentage(GamemodeType.TDM));
        assertEquals(33, votes.getPercentage(GamemodeType.FFA));
    }

    @Test
    void winnerIsTheMostVotedMode() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM, GamemodeType.FFA));
        votes.vote(UUID.randomUUID(), GamemodeType.FFA);
        votes.vote(UUID.randomUUID(), GamemodeType.FFA);
        votes.vote(UUID.randomUUID(), GamemodeType.TDM);

        assertEquals(GamemodeType.FFA, votes.getWinner());
    }

    @Test
    void tiesAndEmptyTalliesStillYieldAPlayableWinner() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM, GamemodeType.FFA));
        // Nobody voted - the round loop must still get a mode it can actually start.
        assertTrue(votes.getOptions().contains(votes.getWinner()));

        votes.vote(UUID.randomUUID(), GamemodeType.TDM);
        votes.vote(UUID.randomUUID(), GamemodeType.FFA);
        assertTrue(votes.getOptions().contains(votes.getWinner()), "a tie must resolve to one of the tied modes");
    }

    @Test
    void resetClearsEveryVote() {
        VoteService votes = service(ALL, List.of(GamemodeType.TDM));
        UUID voter = UUID.randomUUID();
        votes.vote(voter, GamemodeType.TDM);
        votes.reset();

        assertEquals(0, votes.getTotalVotes());
        assertNull(votes.getVote(voter));
        assertFalse(votes.getTally().isEmpty(), "options remain listed even with no votes");
    }
}
