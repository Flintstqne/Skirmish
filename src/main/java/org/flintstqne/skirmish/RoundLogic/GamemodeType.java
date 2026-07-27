package org.flintstqne.skirmish.RoundLogic;

public enum GamemodeType {
    KOTH,
    DOMINATION,
    TDM,
    FFA,
    GUN_GAME;

    /** FFA and Gun Game skip team assignment entirely (design doc §7.2/§9.4/§9.5). */
    public boolean usesTeams() {
        return this == KOTH || this == DOMINATION || this == TDM;
    }
}
