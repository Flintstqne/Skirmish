package org.flintstqne.skirmish.RoundLogic;

/** Round lifecycle (design doc §7.1): WAITING → ACTIVE → ENDING → WAITING. */
public enum RoundState {
    /** No round running — loadout selection open, players held in spawn-protected bases. */
    WAITING,
    /** Round timer running, gamemode logic live. */
    ACTIVE,
    /** EndRoundSequence is running: winner announce → vote → revert → next round. */
    ENDING
}
