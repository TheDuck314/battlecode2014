package seeding;

import java.util.ArrayList;

import battlecode.common.*;

public class BotSoldier extends Bot {
    public static void loop(RobotController theRC) throws Exception {
        init(theRC);
        while (true) {
            try {
                turn();
            } catch (Exception e) {
                e.printStackTrace();
            }
            rc.yield();
        }
    }

    protected static void init(RobotController theRC) throws GameActionException {
        Bot.init(theRC);
//        Debug.init(theRC, "nav");
        Nav.init(theRC);

        spawnOrder = MessageBoard.SPAWN_COUNT.readInt();

        tryReceivePastrLocations();
        tryClaimBuildAssignment();
        tryClaimSuppressorAssignment();
    }

    private enum MicroStance {
        SAFE, // Retreat a lot, play it safe. Appropriate when harrassing or gathering.
        DEFENSIVE, // Play it somewhat safe, but don't retreat too much because we have something to protect. Appropriate for defending
        AGGRESSIVE // Be somewhat reckless when many allies are nearby. Appropriate for attacking.
    }

    static MicroStance stance;

    static MapLocation here;
    static RobotInfo[] visibleEnemies; // enemies within vision radius (35)
    static RobotInfo[] attackableEnemies; // enemies within attack radius(10)
    static int numNonConstructingSoldiersAttackingUs;
    static int numVisibleNonConstructingEnemySoldiers;
    static int[] cachedNumEnemiesAttackingMoveDirs; // computed in doObligatoryMicro
    static boolean tryingSelfDestruct = false;
    static boolean beVeryCautious = false;

    static int spawnOrder;

    static int numPastrLocations = 0;
    static MapLocation[] bestPastrLocations = new MapLocation[BotHQ.MAX_PASTR_LOCATIONS];
    static int towerBuildAssignmentIndex = -1;
    static int pastrBuildAssignmentIndex = -1;
    static int suppressorBuildAssignmentIndex = -1;
    static boolean isFirstTurn = true;

    private static void turn() throws GameActionException {
        here = rc.getLocation();
        if (!rc.isActive()) {
            // can self-destruct even with large actiondelay:
            if (tryingSelfDestruct) {
                updateEnemyData();
                trySelfDestruct();
            }
            return;
        }

        if (Strategy.active == Strategy.UNDECIDED) {
            Strategy.active = MessageBoard.STRATEGY.readStrategy();
            if (Strategy.active == Strategy.UNDECIDED) return; // don't do anything until we know the strategy
        }

        // Don't do anything until we get the list of pastr locations
        if (numPastrLocations == 0) {
            if (!tryReceivePastrLocations()) return;
        }

        if (MessageBoard.PASTR_DISTRESS_SIGNAL.readInt() >= Clock.getRoundNum() - 1) {
            if (mercyKillPastrs()) return;
        }

        updateEnemyData();

        if (tryingSelfDestruct) {
            if (trySelfDestruct()) return;
        }

        MapLocation rallyLoc = MessageBoard.RALLY_LOC.readMapLocation();
        BotHQ.RallyGoal rallyGoal = MessageBoard.RALLY_GOAL.readRallyGoal();
        stance = chooseMicroStance(rallyLoc, rallyGoal);

        if (visibleEnemies.length > 0 && rc.isActive()) {
            doObligatoryMicro();
            if (!rc.isActive()) {
                return;
            }
        }

        if (tryBuildSomething()) {
            return;
        }

        // Check if someone else has taken our job from us:
        if (!isFirstTurn) {
            if (towerBuildAssignmentIndex != -1) {
                if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(towerBuildAssignmentIndex)) towerBuildAssignmentIndex = -1;
            }
            if (pastrBuildAssignmentIndex != -1) {
                if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(pastrBuildAssignmentIndex)) pastrBuildAssignmentIndex = -1;
            }
            if (suppressorBuildAssignmentIndex != -1) {
                if (!MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(suppressorBuildAssignmentIndex)) suppressorBuildAssignmentIndex = -1;
            }
        }
        isFirstTurn = false;

        Nav.Engage navEngage = stance == MicroStance.AGGRESSIVE ? Nav.Engage.YES : Nav.Engage.NO;

        int destIndex = towerBuildAssignmentIndex;
        if (destIndex == -1) destIndex = pastrBuildAssignmentIndex;
        if (destIndex != -1) {
            Nav.goTo(bestPastrLocations[destIndex], Nav.Sneak.NO, navEngage, countNumEnemiesAttackingMoveDirs());
            return;
        }

        if (suppressorBuildAssignmentIndex != -1) {
            MapLocation target = MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.readFromMapLocationList(suppressorBuildAssignmentIndex);
            if (tryBuildSuppressor(target)) return;
            Nav.goTo(target, Nav.Sneak.NO, Nav.Engage.NO, countNumEnemiesAttackingMoveDirs());
            return;
        }

        if (rallyLoc != null) {
            if (weAreNearEnemyPastr()) {
                if (tryToKillCows()) { return; }
            }
            Nav.Sneak sneak = Nav.Sneak.NO;
            for (int i = 0; i < numPastrLocations; i++) {
                if (here.distanceSquaredTo(bestPastrLocations[i]) <= 49) {
                    sneak = Nav.Sneak.YES;
                    break;
                }
            }
            if (visibleEnemies.length > 0) {
                if (doVoluntaryMicro(rallyLoc, rallyGoal, sneak)) {
                    return;
                }
            }
            Nav.goTo(rallyLoc, sneak, navEngage, countNumEnemiesAttackingMoveDirs());
        }
    }

    // returns false if turn() should return, otherwise true
    private static boolean tryReceivePastrLocations() throws GameActionException {
        numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
        if (numPastrLocations == 0) {
            if (Strategy.active == Strategy.PROXY || Strategy.active == Strategy.PROXY_ATTACK || Strategy.active == Strategy.RUSH) return true;
            else return false;
        }

        for (int i = 0; i < numPastrLocations; i++) {
            bestPastrLocations[i] = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
        }

        if (Strategy.active == Strategy.ONE_PASTR || Strategy.active == Strategy.ONE_PASTR_SUPPRESSOR || Strategy.active == Strategy.SCATTER
                || Strategy.active == Strategy.SCATTER_SUPPRESSOR) {
            tryClaimBuildAssignment();
        }
        return true;
    }

    private static void tryClaimBuildAssignment() throws GameActionException {
        // See if we can assign ourselves to a job:
        for (int i = 0; i < numPastrLocations; i++) {
            if (MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
                MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
                towerBuildAssignmentIndex = i;
                break;
            }
        }
        if (towerBuildAssignmentIndex == -1) {
            for (int i = 0; i < numPastrLocations; i++) {
                if (MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
                    MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
                    pastrBuildAssignmentIndex = i;
                    break;
                }
            }
        }
    }

    private static void tryClaimSuppressorAssignment() throws GameActionException {
        int numSuppressors = MessageBoard.NUM_SUPPRESSORS.readInt();
        for (int i = 0; i < numSuppressors; i++) {
            if (MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
                MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.claimAssignment(i);
                suppressorBuildAssignmentIndex = i;
            }
        }
    }

    private static boolean tryBuildSomething() throws GameActionException {
        if (1 + numOtherAlliedSoldiersInRange(here, RobotType.SOLDIER.sensorRadiusSquared) <= 2 * visibleEnemies.length) return false;

        for (int i = 0; i < numPastrLocations; i++) {
            MapLocation pastrLoc = bestPastrLocations[i];
            if (here.equals(pastrLoc)) {
                // claim the pastr build job if someone else thought they were going to do it
                if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(i)) {
                    MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
                }
                if (MessageBoard.BUILD_PASTRS_FAST.readBoolean() || Util.containsNoiseTower(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), rc)) {
                    rc.construct(RobotType.PASTR);
                }
                return true;
            }

            if (here.isAdjacentTo(pastrLoc)) {
                if (!Util.containsConstructingRobotOrNoiseTower(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), rc)) {
                    // claim the tower build job if someone else thought they were going to do it
                    if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
                        MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
                    }
                    constructNoiseTower(pastrLoc);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tryBuildSuppressor(MapLocation target) throws GameActionException {

        if (here.distanceSquaredTo(target) <= RobotType.NOISETOWER.attackRadiusMaxSquared) {
            if (1 + numOtherAlliedSoldiersInRange(here, RobotType.SOLDIER.sensorRadiusSquared) <= 2 * visibleEnemies.length) {
                return true;
            } else {
                rc.construct(RobotType.NOISETOWER);
                return true;
            }
        }
        return false;
    }

    private static void constructNoiseTower(MapLocation pastrLoc) throws GameActionException {
        rc.construct(RobotType.NOISETOWER);
        // HerdPattern.computeAndPublish(here, pastrLoc, rc);
    }

    private static void updateEnemyData() throws GameActionException {
        Robot[] visibleEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);

        visibleEnemies = new RobotInfo[visibleEnemyRobots.length];
        numVisibleNonConstructingEnemySoldiers = 0;
        for (int i = visibleEnemyRobots.length; i-- > 0;) {
            visibleEnemies[i] = rc.senseRobotInfo(visibleEnemyRobots[i]);
            if (visibleEnemies[i].type == RobotType.SOLDIER && !visibleEnemies[i].isConstructing) numVisibleNonConstructingEnemySoldiers++;
        }

        numNonConstructingSoldiersAttackingUs = 0;
        if (visibleEnemies.length > 0) {
            Robot[] attackableEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
            attackableEnemies = new RobotInfo[attackableEnemyRobots.length];
            for (int i = attackableEnemies.length; i-- > 0;) {
                RobotInfo info = rc.senseRobotInfo(attackableEnemyRobots[i]);
                attackableEnemies[i] = info;
                if (info.type == RobotType.SOLDIER && !info.isConstructing) numNonConstructingSoldiersAttackingUs++;
            }
        } else {
            attackableEnemies = new RobotInfo[0];
        }

        // clear the cached value of this array at the beginning of each turn:
        cachedNumEnemiesAttackingMoveDirs = null;
    }

    private static boolean trySelfDestruct() throws GameActionException {
        double maxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * rc.getHealth();
        double damageDealt = 0;
        for (RobotInfo info : attackableEnemies) {
            if (info.location.isAdjacentTo(here) && info.type == RobotType.SOLDIER && !info.isConstructing) {
                damageDealt += Math.min(info.health, maxDamage);
            }
        }
        damageDealt -= maxDamage * rc.senseNearbyGameObjects(Robot.class, here, 2, us).length;
        if (damageDealt > rc.getHealth() * 1.5
                || (damageDealt > rc.getHealth() && rc.getHealth() < 1.5 * RobotType.SOLDIER.attackPower * numNonConstructingSoldiersAttackingUs)) {
            MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(0); // OK to others to self-destruct now
            rc.selfDestruct();
            return true;
        }
        return false;
    }

    private static MicroStance chooseMicroStance(MapLocation rallyLoc, BotHQ.RallyGoal rallyGoal) {
        switch (rallyGoal) {
            case DEFEND:
                if (here.distanceSquaredTo(rallyLoc) > 64) return MicroStance.AGGRESSIVE; // punch through to our pastr
                else return MicroStance.DEFENSIVE; // defend the pastr

            case DESTROY:
                return MicroStance.AGGRESSIVE; // let nothing stand in our way

            case GATHER:
                return MicroStance.SAFE; // Don't waste units

            case HARRASS:
                if (here.distanceSquaredTo(rallyLoc) > 100) return MicroStance.AGGRESSIVE; // punch through to the target
                else return MicroStance.SAFE; // Don't lose units

            default:
                System.out.println("Unknown rally goal!!!");
                return MicroStance.DEFENSIVE;
        }
    }

    private static boolean doVoluntaryMicro(MapLocation rallyLoc, BotHQ.RallyGoal rallyGoal, Nav.Sneak sneak) throws GameActionException {
        switch (rallyGoal) {
            case GATHER:
            case DEFEND:
                if (here.distanceSquaredTo(rallyLoc) <= 36) {
                    MapLocation closestEnemy = Util.closestNonHQ(visibleEnemies, rc);
                    if (closestEnemy == null) return false;
                    int maxEnemyExposure = 0;
                    tryMoveTowardLocationWithMaxEnemyExposureWithinRadius(closestEnemy, maxEnemyExposure, rallyLoc, 36, sneak);
                    return true;
                }
                return false;

            case DESTROY:
                return false;

            case HARRASS:
                if (here.distanceSquaredTo(rallyLoc) <= 81) {
                    tryMoveTowardLocationWithMaxEnemyExposure(rallyLoc, 0, sneak);
                    return true;
                }
                return false;

            default: // shouldn't happen
                return false;
        }
    }

    // obligatory micro is attacks or movements that we have to do because either we or a nearby ally is in combat
    private static boolean doObligatoryMicro() throws GameActionException {
        if (numNonConstructingSoldiersAttackingUs >= 1) { // we are in combat
            // Decide whether to try to move in for a self-destruct
            if (rc.getHealth() > RobotType.SOLDIER.attackPower * numNonConstructingSoldiersAttackingUs) {
                if (Clock.getRoundNum() >= MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.readInt()
                        || rc.getRobot().getID() == MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.readInt()) {
                    double requiredSelfDestructDamage = rc.getHealth() * 1.5;
                    if (tryMoveForSelfDestruct(requiredSelfDestructDamage)) {
                        tryingSelfDestruct = true;
                        return true;
                    } else {
                        tryingSelfDestruct = false;
                    }
                }
            }

            // If we are getting double-teamed worse than any of the enemies we are fighting, try to retreat
            int maxAlliesAttackingEnemy = 0;
            for (int i = attackableEnemies.length; i-- > 0;) {
                // int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersInAttackRange(attackableEnemies[i].location);
                // we deliberately include buildings in this count to encourage our soldiers to defend buildings:
                int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersAndBuildingsInAttackRange(attackableEnemies[i].location);
                if (numAlliesAttackingEnemy > maxAlliesAttackingEnemy) maxAlliesAttackingEnemy = numAlliesAttackingEnemy;
            }
            if (numNonConstructingSoldiersAttackingUs == 1) {
                if (maxAlliesAttackingEnemy == 1) {
                    // we are in a 1v1. fight if we are winning, otherwise retreat
                    RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
                    boolean weAreWinning1v1 = rc.getHealth() >= singleEnemy.health;
                    if (weAreWinning1v1) {
                        attackAndRecord(singleEnemy);
                    } else {
                        if (stance == MicroStance.AGGRESSIVE && guessIfFightIsWinning()) {
                            attackAndRecord(singleEnemy);
                        } else {
                            retreatOrFight();
                        }
                    }
                } else {
                    // we outnumber the lone enemy. kill him
                    RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
                    attackAndRecord(singleEnemy);
                }
            } else if (!guessIfFightIsWinning() && numNonConstructingSoldiersAttackingUs > maxAlliesAttackingEnemy || stance == MicroStance.SAFE) {
                // We are getting doubled teamed too badly.
                retreatOrFight();
            } else {
                // Several enemies can attack us, but we are also double teaming the enemy badly enough to keep fighting
                // Debug.indicate("micro", 0, "attacking");
                attackANonConstructingSoldier();
            }
            return true;
        } else {// we are not in combat.
            // We are only obligated to do something here if a nearby ally is in combat and
            // we can attack a soldier who is attacking them. Then we have to decide whether to move to attack such a soldier
            // and if so which one. Actually, there is one other thing that is obligatory, which is to move to attack
            // helpless enemies (buildings and constructing soldiers) if it is safe to do so.
            if (stance != MicroStance.SAFE) {
                MapLocation closestEnemySoldier = Util.closestNonConstructingSoldier(visibleEnemies, here);
                if (closestEnemySoldier != null) {
                    // int numAlliesFighting = numOtherAlliedSoldiersInAttackRange(closestEnemySoldier);
                    // we deliberately include buildings in this count to encourage our soldiers to defend buildings:
                    int numAlliesFighting = numOtherAlliedSoldiersAndBuildingsInAttackRange(closestEnemySoldier);
                    if (numAlliesFighting > 0) {
                        // Approach this enemy if doing so would expose us to at most numAlliesFighting enemies.
                        int maxEnemyExposure = numAlliesFighting + 1;
                        if (tryMoveTowardLocationWithMaxEnemyExposure(closestEnemySoldier, maxEnemyExposure, Nav.Sneak.NO)) {
                            // Debug.indicate("micro", 0, "moving to support allies fighting closest enemy (max enemy exposure = " + numAlliesFighting + ")");
                            return true;
                        }
                    }
                }
            }

            // If we didn't have to go help an ally, or were unable to, check if there is something nearby we can shoot.
            // If so, shoot it. It must be a constructing solder or building because it's not a non-constructing soldier
            if (attackableEnemies.length > 0) {
                // Actually, we need to prevent the following situation: we can see a noise tower and a pastr, but are only in
                // range of the noise tower, so we attack the noise tower instead of moving to attack the pastr. It's important
                // to not waste any rounds killing pastrs. So check if we can see a pastr but not shoot a pastr
                if (tryMovetoEngageUndefendedPastr()) {
                    return true;
                }

                // Debug.indicate("micro", 0, "attacking a helpless enemy");
                attackAHelplessEnemy();
                return true;
            }

            // We didn't have to help an ally or shoot a helpless enemy. One final possibility with an obligatory response:
            // if we can engage a helpless enemy and not get shot, do it
            if (tryMoveToEngageAnyUndefendedHelplessEnemy()) {
                // Debug.indicate("micro", 0, "moving to engage a helpless enemy");
                return true;
            }

            // If none of the above cases compelled us to action, there is no obligatory action
            return false;
        }
    }

    private static boolean guessIfFightIsWinning() {
        switch (numVisibleNonConstructingEnemySoldiers) {
            case 0:
                return true;
            case 1:
                return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 2;
            case 2:
                return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 4;
            case 3:
                return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 5;
            default:
                return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= (int) (1.5 * numVisibleNonConstructingEnemySoldiers);
        }
    }

    // @formatter:off
//	private static void tmp() {
//		MapLocation center = new MapLocation(0, 0);
//		for (int ex = -3; ex <= +3; ex++) {
//			System.out.print("{");
//			for (int ey = -3; ey <= +3; ey++) {
//				MapLocation enemyLoc = new MapLocation(ex, ey);
//				ArrayList<Integer> attacked = new ArrayList<Integer>();
//				for (int dir = 0; dir < 8; dir++) {
//					MapLocation moveLoc = center.add(Direction.values()[dir]);
//					if (moveLoc.isAdjacentTo(enemyLoc)) attacked.add(dir);
//				}
//				System.out.print("{");
//				for (int i = 0; i < attacked.size(); i++) {
//					System.out.print(attacked.get(i));
//					if (i < attacked.size() - 1) System.out.print(",");
//				}
//				System.out.print("}");
//				if (ey < +5) {
//					System.out.print(",");
//					int spaces = Math.min(16, 17 - 2 * attacked.size());
//					for (int i = 0; i < spaces; i++)
//						System.out.print(" ");
//				}
//			}
//			System.out.println("}");
//		}
//	}
	// selfDestructNotes[3+dx][3+dy] is a list of directions such that one move in that direction brings you adjacent to
	// an enemy at (here.x + dx, here.y + dy)
    private static final int[][][] selfDestructNotes = {{{},                {},                {},                {},                {},                {},                {},                },
                                                        {{},                {7},               {6,7},             {5,6,7},           {5,6},             {5},               {},                },
                                                        {{},                {0,7},             {0,6},             {0,4,5,7},         {4,6},             {4,5},             {},                },
                                                        {{},                {0,1,7},           {1,2,6,7},         {0,1,2,3,4,5,6,7}, {2,3,5,6},         {3,4,5},           {},                },
                                                        {{},                {0,1},             {0,2},             {0,1,3,4},         {2,4},             {3,4},             {},                },
                                                        {{},                {1},               {1,2},             {1,2,3},           {2,3},             {3},               {},                },
                                                        {{},                {},                {},                {},                {},                {},                {},                }};
	// @formatter:on

    private static boolean tryMoveForSelfDestruct(double requiredDamage) throws GameActionException {
        double maxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * rc.getHealth();
        double[] moveScores = new double[8];
        for (RobotInfo info : attackableEnemies) {
            if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
            double score = Math.min(maxDamage, info.health);
            int[] moves = selfDestructNotes[3 + info.location.x - here.x][3 + info.location.y - here.y];
            for (int dir : moves) {
                moveScores[dir] += score;
            }
        }
        double bestScore = requiredDamage;
        int bestDir = -1;
        Direction[] dirs = Direction.values();
        for (int i = 8; i-- > 0;) {
            if (moveScores[i] > bestScore && rc.canMove(dirs[i])) {
                bestScore = moveScores[i];
                bestDir = i;
            }
        }
        if (bestDir != -1) {
            if (rc.senseNearbyGameObjects(Robot.class, here.add(dirs[bestDir]), 2, us).length == 0) {
                rc.move(dirs[bestDir]);
                // Tell others not to go for a self-destruct until the round after we self-destruct
                MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.writeInt(rc.getRobot().getID());
                MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(Clock.getRoundNum() + 2);
                return true;
            }
        }
        return false;
    }

    private static boolean tryMovetoEngageUndefendedPastr() throws GameActionException {
        boolean canSeePastr = false;
        MapLocation pastrLoc = null;
        for (int i = visibleEnemies.length; i-- > 0;) {
            if (visibleEnemies[i].type == RobotType.PASTR) {
                canSeePastr = true;
                pastrLoc = visibleEnemies[i].location;
                if (here.distanceSquaredTo(pastrLoc) <= RobotType.SOLDIER.attackRadiusMaxSquared) return false; // we can shoot a pastr, we don't need to move
            }
        }
        if (!canSeePastr) return false;

        Direction toPastr = here.directionTo(pastrLoc);
        if (!rc.canMove(toPastr)) return false;

        MapLocation pathLoc = here;
        while (pathLoc.distanceSquaredTo(pastrLoc) > RobotType.SOLDIER.attackRadiusMaxSquared) {
            pathLoc = pathLoc.add(pathLoc.directionTo(pastrLoc));
            if (rc.senseTerrainTile(pathLoc) == TerrainTile.VOID) return false;
            if (isInTheirHQAttackRange(pathLoc)) return false;
            Robot[] enemyRobotsAlongPath = rc.senseNearbyGameObjects(Robot.class, pathLoc, RobotType.SOLDIER.attackRadiusMaxSquared, them);
            if (Util.containsNonConstructingSoldier(enemyRobotsAlongPath, rc)) return false;
        }

        rc.move(toPastr);
        return true;
    }

    private static boolean tryMoveToEngageAnyUndefendedHelplessEnemy() throws GameActionException {
        visibleEnemyLoop: for (int i = visibleEnemies.length; i-- > 0;) {
            RobotInfo enemy = visibleEnemies[i];
            if (Util.isHelpless(enemy)) {
                MapLocation enemyLocation = enemy.location;
                // Debug.indicate("micro", 1, "see helpless enemy at " + enemyLocation.toString());
                Direction toEnemyDir = here.directionTo(enemyLocation);
                if (!rc.canMove(toEnemyDir)) continue visibleEnemyLoop;
                MapLocation pathLoc = here;
                while (pathLoc.distanceSquaredTo(enemyLocation) > RobotType.SOLDIER.attackRadiusMaxSquared) {
                    pathLoc = pathLoc.add(pathLoc.directionTo(enemyLocation));
                    if (rc.senseTerrainTile(pathLoc) == TerrainTile.VOID) continue visibleEnemyLoop;
                    if (isInTheirHQAttackRange(pathLoc)) continue visibleEnemyLoop;
                    Robot[] enemyRobotsAlongPath = rc.senseNearbyGameObjects(Robot.class, pathLoc, RobotType.SOLDIER.attackRadiusMaxSquared, them);
                    if (Util.containsNonConstructingSoldier(enemyRobotsAlongPath, rc)) continue visibleEnemyLoop;
                }

                // the direct path to attack this helpless enemy is free of enemy soldiers. start moving along it
                rc.move(toEnemyDir);
                return true;
            }
        }
        return false;
    }

    private static boolean tryMoveTowardLocationWithMaxEnemyExposure(MapLocation dest, int maxEnemyExposure, Nav.Sneak sneak) throws GameActionException {
        int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

        Direction toEnemy = here.directionTo(dest);
        Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
        for (int i = 0; i < tryDirs.length; i++) {
            Direction tryDir = tryDirs[i];
            if (!rc.canMove(tryDir)) continue;
            if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) continue;
            if (isInTheirHQAttackRange(here.add(tryDir))) continue;
            // Debug.indicate("micro", 1, String.format("moving toward %s with max enemy exposure %d (actual exposure %d)", dest.toString(), maxEnemyExposure,
            // numEnemiesAttackingDirs[tryDir.ordinal()]));
            if (sneak == Nav.Sneak.YES) rc.move(tryDir);
            else rc.sneak(tryDir);
            return true;
        }
        // Debug.indicate("micro", 1, String.format("can't move toward %s with max enemy exposure %d", dest.toString(), maxEnemyExposure));
        return false;
    }

    private static boolean tryMoveTowardLocationWithMaxEnemyExposureWithinRadius(MapLocation dest, int maxEnemyExposure, MapLocation center, int maxRadiusSq,
            Nav.Sneak sneak) throws GameActionException {
        int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

        Direction toEnemy = here.directionTo(dest);
        Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
        for (int i = 0; i < tryDirs.length; i++) {
            Direction tryDir = tryDirs[i];
            if (!rc.canMove(tryDir)) continue;
            if (center.distanceSquaredTo(here.add(tryDir)) > maxRadiusSq) continue;
            if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) continue;
            if (isInTheirHQAttackRange(here.add(tryDir))) continue;
            // Debug.indicate("micro", 1, String.format("moving toward %s with max enemy exposure %d (actual exposure %d)", dest.toString(), maxEnemyExposure,
            // numEnemiesAttackingDirs[tryDir.ordinal()]));
            if (sneak == Nav.Sneak.YES) rc.move(tryDir);
            else rc.sneak(tryDir);
            return true;
        }
        // Debug.indicate("micro", 1, String.format("can't move toward %s with max enemy exposure %d", dest.toString(), maxEnemyExposure));
        return false;
    }

    // Costs ~1k bytecodes :(
    private static boolean tryToKillCows() throws GameActionException {
        MapLocation bestTarget = null;
        double mostCows = 300;
        MapLocation[] shootLocs = MapLocation.getAllMapLocationsWithinRadiusSq(here, RobotType.SOLDIER.attackRadiusMaxSquared);
        for (int i = shootLocs.length; i-- > 0;) { // 4 per loop
            double cows = rc.senseCowsAtLocation(shootLocs[i]); // 14
            if (cows > mostCows) { // 4
                MapLocation target = shootLocs[i];
                if (target.equals(here)) continue;
                if (cows > 3000 || rc.senseNearbyGameObjects(Robot.class, target, 0, us).length == 0) {
                    mostCows = cows;
                    bestTarget = target;
                }
            }
        }
        if (bestTarget != null) {
            rc.attackSquare(bestTarget);
            return true;
        } else {
            return false;
        }
    }

    private static int numOtherAlliedSoldiersAndBuildingsInAttackRange(MapLocation loc) {
        return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us).length;
    }

    private static int numOtherAlliedSoldiersInAttackRange(MapLocation loc) throws GameActionException {
        return numOtherAlliedSoldiersInRange(loc, RobotType.SOLDIER.attackRadiusMaxSquared);
    }

    private static int numOtherAlliedSoldiersInRange(MapLocation loc, int rangeSq) throws GameActionException {
        int numAlliedSoldiers = 0;
        Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, rangeSq, us);
        for (int i = allies.length; i-- > 0;) {
            if (rc.senseRobotInfo(allies[i]).type == RobotType.SOLDIER) numAlliedSoldiers++;
        }
        return numAlliedSoldiers;
    }

    // Assumes attackableEnemies contains a non-constructing soldier
    private static void attackANonConstructingSoldier() throws GameActionException {
        RobotInfo target = chooseNonConstructingSoldierAttackTarget(attackableEnemies);
        attackAndRecord(target);
    }

    private static RobotInfo chooseNonConstructingSoldierAttackTarget(RobotInfo[] enemies) throws GameActionException {
        RobotInfo ret = null;
        double bestTurnsToKill = 999;
        double bestActionDelay = 999;
        for (int i = enemies.length; i-- > 0;) {
            RobotInfo info = enemies[i];
            if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
            int numNearbyAllies = 1 + numOtherAlliedSoldiersInAttackRange(info.location);
            double turnsToKill = info.health / numNearbyAllies;
            if (turnsToKill < bestTurnsToKill) {
                bestTurnsToKill = turnsToKill;
                bestActionDelay = info.actionDelay;
                ret = info;
            } else if (turnsToKill == bestTurnsToKill) {
                double actionDelay = info.actionDelay;
                if (actionDelay < bestActionDelay) {
                    bestActionDelay = actionDelay;
                    ret = info;
                }
            }
        }
        // Debug.indicate("micro", 2, "chooseNonConstructingSoldierAttackTarget: target = " + ret.location.toString() + ", bestTurnsToKill = " +
        // bestTurnsToKill);
        return ret;
    }

    // Assumes attackableEnemies is non-empty and only contains helpless enemies
    private static void attackAHelplessEnemy() throws GameActionException {
        RobotInfo target = chooseHelplessAttackTarget();
        attackAndRecord(target);
    }

    // If we can only attack buildings or constructing soldiers, this function decides which one to attack.
    // It assumes that the enemies list does not contain any non-constructing soldiers or HQs!
    private static RobotInfo chooseHelplessAttackTarget() throws GameActionException {
        RobotInfo ret = null;
        double bestHealth = 999999;
        RobotType bestType = RobotType.SOLDIER;
        for (int i = attackableEnemies.length; i-- > 0;) {
            RobotInfo info = attackableEnemies[i];
            RobotType type = info.type;
            if (type == bestType) { // break ties between equal types by health
                double health = info.health;
                if (health < bestHealth) {
                    bestHealth = health;
                    ret = info;
                }
            } else if (type == RobotType.PASTR || bestType == RobotType.SOLDIER) { // prefer pastrs to noise towers to constructing soldiers
                bestType = type;
                bestHealth = info.health;
                ret = info;
            }
        }
        return ret;
    }

    private static void retreatOrFight() throws GameActionException {
        // If all our opponents have really high action delay, we can fire a last shot
        // and still be able to move before they can return fire. This would most probably
        // happen if an enemy engaged us after several diagonal moves. This could turn
        // a losing 1v1 into a winning one! Also, if we can one-hit an enemy we should
        // do so instead of retreating even if we take hits to do so
        boolean canOneHitEnemy = false;
        boolean enemyCanShootAtUs = false;
        for (int i = attackableEnemies.length; i-- > 0;) {
            RobotInfo enemy = attackableEnemies[i];
            if (enemy.health <= RobotType.SOLDIER.attackPower) {
                canOneHitEnemy = true;
                break;
            }
            if (enemy.actionDelay < 3.0 && !enemy.isConstructing) {
                enemyCanShootAtUs = true;
            }
        }

        if (canOneHitEnemy || !enemyCanShootAtUs) {
            // Debug.indicate("micro", 2, "parthian shot (canOneHitEnemy = " + canOneHitEnemy + ", enemyCanShootAtUs = " + enemyCanShootAtUs + ")");
            attackANonConstructingSoldier();
            return;
        }

        Direction dir = chooseRetreatDirection();
        if (dir == null) { // Can't retreat! Fight!
            // Debug.indicate("micro", 2, "couldn't retreat; fighting instead");
            attackANonConstructingSoldier();
        } else { // Can retreat. Do it!
            // Debug.indicate("micro", 2, "retreating successfully");
            rc.move(dir);
        }
    }

    // @formatter:off
	// attackNotes[5+dx][5+dy] is a list of integers dir such that a soldier at here + (dx, dy) can attack the square here.add(Direction.values()[dir])
	// It was generated by this code:
	//
	// void tmp() {
	//	MapLocation center = new MapLocation(0, 0);
	//	for(int ex = -5; ex <= +5; ex++) {
	//		System.out.print("{");
	//		for(int ey = -5; ey <= +5; ey++) {
	//			MapLocation enemyLoc = new MapLocation(ex, ey);
	//			ArrayList<Integer> attacked = new ArrayList<Integer>();
	//			for(int dir = 0; dir < 8; dir++) {
	//				MapLocation moveLoc = center.add(Direction.values()[dir]);
	//				if(moveLoc.distanceSquaredTo(enemyLoc) <= 10) attacked.add(dir);
	//			}
	//			System.out.print("{");
	//			for(int i = 0; i < attacked.size(); i++) {
	//				System.out.print(attacked.get(i));
	//				if(i < attacked.size() - 1) System.out.print(",");
	//			}
	//			System.out.print("}");
	//			if(ey < +5) {
	//				System.out.print(",");				
	//				int spaces = Math.min(16, 17 - 2*attacked.size());
	//				for(int i = 0; i < spaces; i++) System.out.print(" ");
	//			}
	//		}
	//		System.out.println("}");
	//	}
	// }
	private static int[][][] attackNotes = {{{},                {},                {},                {},                {},                {},                {},                {},                {},                {},                {}},
                                            {{},                {},                {},                {7},               {6,7},             {5,6,7},           {5,6},             {5},               {},                {},                {}},
                                            {{},                {},                {7},               {0,6,7},           {0,5,6,7},         {0,4,5,6,7},       {4,5,6,7},         {4,5,6},           {5},               {},                {}},
                                            {{},                {7},               {0,6,7},           {0,1,5,6,7},       {0,1,2,4,5,6,7},   {0,1,2,3,4,5,6,7}, {0,2,3,4,5,6,7},   {3,4,5,6,7},       {4,5,6},           {5},               {}},
                                            {{},                {0,7},             {0,1,6,7},         {0,1,2,4,5,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,2,3,4,5,6,7},   {3,4,5,6},         {4,5},             {}},
                                            {{},                {0,1,7},           {0,1,2,6,7},       {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {2,3,4,5,6},       {3,4,5},           {}},
                                            {{},                {0,1},             {0,1,2,7},         {0,1,2,3,4,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6},   {2,3,4,5},         {3,4},             {}},
                                            {{},                {1},               {0,1,2},           {0,1,2,3,7},       {0,1,2,3,4,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6},   {1,2,3,4,5},       {2,3,4},           {3},               {}},
                                            {{},                {},                {1},               {0,1,2},           {0,1,2,3},         {0,1,2,3,4},       {1,2,3,4},         {2,3,4},           {3},               {},                {}},
                                            {{},                {},                {},                {1},               {1,2},             {1,2,3},           {2,3},             {3},               {},                {},                {}},
                                            {{},                {},                {},                {},                {},                {},                {},                {},                {},                {},                {}}};
   	// @formatter:on

    private static int[] countNumEnemiesAttackingMoveDirs() {
        if (cachedNumEnemiesAttackingMoveDirs != null) return cachedNumEnemiesAttackingMoveDirs;

        int[] numEnemiesAttackingDir = new int[8];
        for (int i = visibleEnemies.length; i-- > 0;) {
            RobotInfo info = visibleEnemies[i];
            if (info.type == RobotType.SOLDIER && !info.isConstructing) {
                MapLocation enemyLoc = info.location;
                int[] attackedDirs = attackNotes[5 + enemyLoc.x - here.x][5 + enemyLoc.y - here.y];
                for (int j = attackedDirs.length; j-- > 0;) {
                    numEnemiesAttackingDir[attackedDirs[j]]++;
                }
            }
        }
        cachedNumEnemiesAttackingMoveDirs = numEnemiesAttackingDir;
        return numEnemiesAttackingDir;
    }

    private static Direction chooseRetreatDirection() throws GameActionException {
        int repelX = 0;
        int repelY = 0;
        for (int i = visibleEnemies.length; i-- > 0;) {
            Direction repelDir = visibleEnemies[i].location.directionTo(here);
            repelX += repelDir.dx;
            repelY += repelDir.dy;
        }
        int absRepelX = Math.abs(repelX);
        int absRepelY = Math.abs(repelY);
        Direction retreatDir;
        if (absRepelX >= 1.5 * absRepelY) {
            retreatDir = repelX > 0 ? Direction.EAST : Direction.WEST;
        } else if (absRepelY >= 1.5 * absRepelX) {
            retreatDir = repelY > 0 ? Direction.SOUTH : Direction.NORTH;
        } else if (repelX > 0) {
            retreatDir = repelY > 0 ? Direction.SOUTH_EAST : Direction.NORTH_EAST;
        } else {
            retreatDir = repelY > 0 ? Direction.SOUTH_WEST : Direction.NORTH_WEST;
        }

        // Test to see if retreatDir, or either of the adjacent directions, actually work
        // To work, three conditions have to be satisfied:
        // (a) we have to be able to move in that direction
        // (b) moving in that direction has to take us out of range of enemy attacks
        // (c) moving in that direction can't take us within range of the enemy HQ
        int bestMinEnemyDistSq = 999999;
        for (int j = visibleEnemies.length; j-- > 0;) {
            int enemyDistSq = here.distanceSquaredTo(visibleEnemies[j].location);
            if (enemyDistSq < bestMinEnemyDistSq) bestMinEnemyDistSq = enemyDistSq;
        }
        Direction bestDir = null;
        int[] tryDirs = new int[] { 0, 1, -1, 2, -2, 3, -3, 4 };
        for (int i = 0; i < tryDirs.length; i++) {
            Direction tryDir = Direction.values()[(retreatDir.ordinal() + tryDirs[i] + 8) % 8];
            if (!rc.canMove(tryDir)) continue;
            MapLocation tryLoc = here.add(tryDir);
            if (isInTheirHQAttackRange(tryLoc)) continue;

            int minEnemyDistSq = 999999;
            for (int j = visibleEnemies.length; j-- > 0;) {
                int enemyDistSq = tryLoc.distanceSquaredTo(visibleEnemies[j].location);
                if (enemyDistSq < minEnemyDistSq) minEnemyDistSq = enemyDistSq;
            }
            if (minEnemyDistSq > RobotType.SOLDIER.attackRadiusMaxSquared) return tryDir; // we can escape!!
            if (minEnemyDistSq > bestMinEnemyDistSq) {
                bestMinEnemyDistSq = minEnemyDistSq;
                bestDir = tryDir;
            }
        }

        return bestDir;
    }

    // If one of our pastrs is within our attack range and is about to die, kill it so that the
    // other team doesn't get milk for it.
    private static boolean mercyKillPastrs() throws GameActionException {
        MapLocation[] ourPastrs = rc.sensePastrLocations(us);
        for (int i = ourPastrs.length; i-- > 0;) {
            MapLocation pastrLoc = ourPastrs[i];
            if (here.distanceSquaredTo(ourPastrs[i]) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
                if (rc.senseRobotInfo(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 0, us)[0]).health <= RobotType.SOLDIER.attackPower) {
                    rc.attackSquare(pastrLoc);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean weAreNearEnemyPastr() {
        MapLocation[] enemyPastrs = rc.sensePastrLocations(them);
        int smallestEnemyDistSq = 999999;
        for (int i = enemyPastrs.length; i-- > 0;) {
            int distSq = here.distanceSquaredTo(enemyPastrs[i]);
            if (distSq < smallestEnemyDistSq) smallestEnemyDistSq = distSq;
        }
        if (smallestEnemyDistSq > 70) return false;
        int smallestAllyDistSq = 999999;
        for (int i = numPastrLocations; i-- > 0;) {
            int distSq = here.distanceSquaredTo(bestPastrLocations[i]);
            if (distSq < smallestAllyDistSq) smallestAllyDistSq = distSq;
        }
        return smallestEnemyDistSq < smallestAllyDistSq;
    }

    private static void attackAndRecord(RobotInfo enemyInfo) throws GameActionException {
        if (enemyInfo == null) return; // should never happen, but just to be sure
        rc.attackSquare(enemyInfo.location);
        if (enemyInfo.health <= RobotType.SOLDIER.attackPower) MessageBoard.ROUND_KILL_COUNT.incrementInt();
    }

}
