package com.scorepvp.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents an active match between fighters (duel / queue / FFA).
 *
 * Duel mode: First-to-N. The match ends the moment one fighter reaches
 *   {@code totalRounds} wins (e.g. totalRounds=3 → first player to 3 wins).
 * FFA mode:  All fighters fight until one remains.
 */
public class Match {

    public enum State { SEARCHING, COUNTDOWN, ACTIVE, ROUND_END, RESTORING, FINISHED }

    private final UUID matchId   = UUID.randomUUID();
    private final long createdAt  = System.currentTimeMillis();
    private final Arena arena;
    private final PvPMode mode;
    private final int totalRounds;

    // ── Duel fields ────────────────────────────────────────────────────────────
    private final MatchPlayer fighter1;
    private final MatchPlayer fighter2;

    // ── FFA fields ─────────────────────────────────────────────────────────────
    private final boolean ffa;
    private final List<MatchPlayer> ffaFighters;

    private final Map<UUID, MatchPlayer> spectators = new LinkedHashMap<>();

    private State state = State.SEARCHING;
    private int currentRound = 1;

    /** True when match ended by forfeit (disconnect). */
    private boolean forfeit = false;

    /**
     * True when the challenger opted into custom hats for THIS duel (the
     * post-round-count opt-in menu). When set, a fighter who owns a cosmetic hat
     * has that hat's model stamped onto their kit helmet; everyone else keeps the
     * plain kit helmet. Only ever true for /duel matches — queue/FFA leave it false.
     */
    private boolean withHats = false;

    /**
     * Random per-match team assignment for the duel scoreboard. When true,
     * fighter1 is the RED side and fighter2 is BLUE; when false the reverse.
     * Coin-flipped once at construction so the colors aren't always the same
     * player — each viewer still sees THEMSELVES in one fixed color and the
     * opponent in the other via the scoreboard's %you_color%/%opp_color%.
     * Nametags stay white regardless (no team tint on the nick).
     */
    private final boolean fighter1Red;

    // ── Duel constructor ───────────────────────────────────────────────────────
    public Match(Arena arena, PvPMode mode, int totalRounds, MatchPlayer f1, MatchPlayer f2) {
        this.arena       = arena;
        this.mode        = mode;
        this.totalRounds = totalRounds;
        this.fighter1    = f1;
        this.fighter2    = f2;
        this.ffa         = false;
        this.ffaFighters = null;
        this.fighter1Red = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
    }

    // ── FFA constructor ────────────────────────────────────────────────────────
    public Match(Arena arena, PvPMode mode, List<MatchPlayer> fighters) {
        this.arena        = arena;
        this.mode         = mode;
        this.totalRounds  = 1;
        this.fighter1     = null;
        this.fighter2     = null;
        this.ffa          = true;
        this.ffaFighters  = new ArrayList<>(fighters);
        this.fighter1Red  = false; // unused for FFA
    }

    // ── Duel team colors (scoreboard only) ──────────────────────────────────────
    /** True when fighter1 is the RED side this match (random per match). */
    public boolean isFighter1Red() { return fighter1Red; }

    /** True when the given fighter is on the RED side. */
    public boolean isRed(UUID uuid) {
        if (fighter1 != null && fighter1.getUuid().equals(uuid)) return fighter1Red;
        if (fighter2 != null && fighter2.getUuid().equals(uuid)) return !fighter1Red;
        return false;
    }

    // ── FFA queries ────────────────────────────────────────────────────────────
    public boolean isFfa() { return ffa; }

    public List<MatchPlayer> getFfaFighters() {
        return ffaFighters == null ? List.of() : Collections.unmodifiableList(ffaFighters);
    }

    public List<MatchPlayer> getAliveFfaFighters() {
        if (ffaFighters == null) return List.of();
        return ffaFighters.stream()
                .filter(mp -> !mp.isDead() && mp.isFighter())
                .collect(Collectors.toList());
    }

    public void markFfaFighterDead(UUID uuid) {
        if (ffaFighters == null) return;
        for (MatchPlayer mp : ffaFighters) {
            if (mp.getUuid().equals(uuid)) {
                mp.setDead(true);
                mp.setRole(MatchPlayer.Role.SPECTATOR);
                return;
            }
        }
    }

    // ── Rounds ────────────────────────────────────────────────────────────────

    /**
     * Duel is first-to-N: finished as soon as either fighter reaches
     * {@code totalRounds} wins. So totalRounds=3 means "first to win 3 rounds".
     *
     * For FFA: finished when only 1 alive fighter remains.
     * For forfeit: always finished immediately.
     */
    public boolean isFinished() {
        if (ffa) {
            return getAliveFfaFighters().size() <= 1;
        }
        if (fighter1 == null || fighter2 == null) return true;
        return fighter1.getWins() >= totalRounds || fighter2.getWins() >= totalRounds;
    }

    /**
     * Returns the leading player in a duel based on win count.
     * During play this is the current leader; after isFinished() it is the winner.
     * Returns null if tied.
     */
    public MatchPlayer getWinner() {
        if (ffa) {
            List<MatchPlayer> alive = getAliveFfaFighters();
            if (alive.size() == 1) return alive.get(0);
            if (alive.isEmpty() && ffaFighters != null && !ffaFighters.isEmpty()) {
                return ffaFighters.get(ffaFighters.size() - 1);
            }
            return null;
        }
        if (fighter1 == null || fighter2 == null) return null;
        if (fighter1.getWins() > fighter2.getWins()) return fighter1;
        if (fighter2.getWins() > fighter1.getWins()) return fighter2;
        return null; // tie
    }

    public MatchPlayer getLoser() {
        if (ffa) return null;
        MatchPlayer w = getWinner();
        if (w == null) return null;
        return w == fighter1 ? fighter2 : fighter1;
    }

    public MatchPlayer getOpponent(UUID uuid) {
        if (ffa) return null;
        if (fighter1 != null && fighter1.getUuid().equals(uuid)) return fighter2;
        if (fighter2 != null && fighter2.getUuid().equals(uuid)) return fighter1;
        return null;
    }

    // ── Participants ──────────────────────────────────────────────────────────
    public MatchPlayer getFighter1()       { return fighter1; }
    public MatchPlayer getFighter2()       { return fighter2; }

    public MatchPlayer getFighter(UUID uuid) {
        if (ffa) {
            if (ffaFighters == null) return null;
            for (MatchPlayer mp : ffaFighters)
                if (mp.getUuid().equals(uuid)) return mp;
            return null;
        }
        if (fighter1 != null && fighter1.getUuid().equals(uuid)) return fighter1;
        if (fighter2 != null && fighter2.getUuid().equals(uuid)) return fighter2;
        return null;
    }

    public boolean isFighter(UUID uuid) {
        if (ffa) {
            if (ffaFighters == null) return false;
            return ffaFighters.stream().anyMatch(mp -> mp.getUuid().equals(uuid) && mp.isFighter());
        }
        return (fighter1 != null && fighter1.getUuid().equals(uuid))
            || (fighter2 != null && fighter2.getUuid().equals(uuid));
    }

    public boolean isParticipant(UUID uuid) {
        return isFighter(uuid) || spectators.containsKey(uuid)
                || (ffa && ffaFighters != null && ffaFighters.stream().anyMatch(mp -> mp.getUuid().equals(uuid)));
    }

    public void addSpectator(MatchPlayer mp)    { spectators.put(mp.getUuid(), mp); }
    public void removeSpectator(UUID uuid)      { spectators.remove(uuid); }
    public Map<UUID, MatchPlayer> getSpectators(){ return Collections.unmodifiableMap(spectators); }

    public Set<UUID> getAllUuids() {
        Set<UUID> all = new LinkedHashSet<>();
        if (ffa && ffaFighters != null) {
            ffaFighters.forEach(mp -> all.add(mp.getUuid()));
        } else {
            if (fighter1 != null) all.add(fighter1.getUuid());
            if (fighter2 != null) all.add(fighter2.getUuid());
        }
        all.addAll(spectators.keySet());
        return all;
    }

    /**
     * Returns raw score data for this viewer.
     * For FFA: returns alive count as negative number (e.g. -3 means 3 alive).
     * For duel: returns self wins / other wins string.
     * Formatting should be done by the caller using lang keys.
     */
    public int getFfaAliveCount() {
        return getAliveFfaFighters().size();
    }

    public String getScoreFor(UUID viewer) {
        if (ffa) {
            int alive = getAliveFfaFighters().size();
            return "&e" + alive;
        }
        MatchPlayer self  = getFighter(viewer);
        MatchPlayer other = getOpponent(viewer);
        if (self == null || other == null) {
            return "&b" + (fighter1 != null ? fighter1.getWins() : 0)
                 + " &7: &c" + (fighter2 != null ? fighter2.getWins() : 0);
        }
        return "&b" + self.getWins() + " &7: &c" + other.getWins();
    }

    // ── Forfeit ───────────────────────────────────────────────────────────────
    public boolean isForfeit()       { return forfeit; }
    public void    setForfeit(boolean b) { this.forfeit = b; }

    // ── Duel hats opt-in ────────────────────────────────────────────────────────
    /** True when this duel was created with the "custom hats" option enabled. */
    public boolean isWithHats()          { return withHats; }
    public void    setWithHats(boolean b) { this.withHats = b; }

    // ── Getters ───────────────────────────────────────────────────────────────
    public UUID    getMatchId()      { return matchId; }
    public long    getCreatedAt()    { return createdAt; }
    public boolean isExpired(long maxMinutes) {
        return System.currentTimeMillis() - createdAt > maxMinutes * 60_000L;
    }
    public Arena   getArena()        { return arena; }
    public PvPMode getMode()         { return mode; }
    public int     getTotalRounds()  { return totalRounds; }
    public State   getState()        { return state; }
    public void    setState(State s) { this.state = s; }
    public int     getCurrentRound() { return currentRound; }
    public void    nextRound()       { currentRound++; }

    /** First-to-N: the number of round wins required to win the match. */
    public int winsNeeded()          { return totalRounds; }
}
