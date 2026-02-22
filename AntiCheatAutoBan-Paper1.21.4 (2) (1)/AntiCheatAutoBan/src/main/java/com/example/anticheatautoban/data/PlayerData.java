package com.example.anticheatautoban.data;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-player state tracked across events.
 * One instance exists per online player, created on join and removed on quit.
 */
public class PlayerData {

    // --- Violation counters ---
    private int flyViolations      = 0;
    private int speedViolations    = 0;
    private int freecamViolations  = 0;
    private int xrayViolations     = 0;

    // --- Freecam tracking ---
    private Location lastKnownLocation = null;
    private long     lastMoveTime      = System.currentTimeMillis();
    private boolean  wasStationary     = false;

    // --- Fly / jump tracking ---
    // Counts consecutive move events where the player is airborne with upward velocity.
    // A normal jump sustains upward movement for ~6-8 ticks before gravity kicks in.
    // A fly hack sustains it indefinitely — we flag only after 12+ ticks.
    private int airborneUpwardTicks = 0;

    // --- XRay streak/burst tracking ---
    private int consecutiveRareOres = 0;
    private final Deque<Long> recentRareOreTimes = new ArrayDeque<>();

    // ---- Fly ----
    public int  getFlyViolations()          { return flyViolations; }
    public int  incrementFlyViolations()    { return ++flyViolations; }
    public void resetFlyViolations()        { flyViolations = 0; }
    public void decayFlyViolations()        { if (flyViolations > 0) flyViolations--; }

    // ---- Speed ----
    public int  getSpeedViolations()        { return speedViolations; }
    public int  incrementSpeedViolations()  { return ++speedViolations; }
    public void resetSpeedViolations()      { speedViolations = 0; }
    public void decaySpeedViolations()      { if (speedViolations > 0) speedViolations--; }

    // ---- Freecam ----
    public int  getFreecamViolations()          { return freecamViolations; }
    public int  incrementFreecamViolations()    { return ++freecamViolations; }
    public void resetFreecamViolations()        { freecamViolations = 0; }
    public void decayFreecamViolations()        { if (freecamViolations > 0) freecamViolations--; }

    // ---- XRay ----
    public int  getXrayViolations()         { return xrayViolations; }
    public int  incrementXrayViolations()   { return ++xrayViolations; }
    public void resetXrayViolations()       { xrayViolations = 0; }
    public void decayXrayViolations()       { if (xrayViolations > 0) xrayViolations--; }

    // ---- Freecam position tracking ----
    public Location getLastKnownLocation()           { return lastKnownLocation; }
    public void     setLastKnownLocation(Location l) { this.lastKnownLocation = l; }
    public long     getLastMoveTime()                { return lastMoveTime; }
    public void     setLastMoveTime(long t)          { this.lastMoveTime = t; }
    public boolean  wasStationary()                  { return wasStationary; }
    public void     setWasStationary(boolean b)      { this.wasStationary = b; }

    // ---- Fly airborne tracking ----
    public int  getAirborneUpwardTicks()      { return airborneUpwardTicks; }
    public int  incrementAirborneUpwardTicks(){ return ++airborneUpwardTicks; }
    public void resetAirborneUpwardTicks()    { airborneUpwardTicks = 0; }

    // ---- XRay ore tracking ----
    public int      getConsecutiveRareOres()       { return consecutiveRareOres; }
    public void     incrementConsecutiveRareOres() { consecutiveRareOres++; }
    public void     resetConsecutiveRareOres()     { consecutiveRareOres = 0; }
    public Deque<Long> getRecentRareOreTimes()     { return recentRareOreTimes; }
}
