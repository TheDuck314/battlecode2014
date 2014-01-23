package anatid21_stonewall_attack;

import battlecode.common.*;

public class BotHQ extends Bot {
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
        Debug.init(rc, "suppressor");

        cowGrowth = rc.senseCowGrowth();
        MessageBoard.setDefaultChannelValues();
    }

    // Strategic info
    static int virtualSpawnCountdown = 0;
    static int maxEnemySpawns;
    static int numEnemyPastrs;
    static int maxEnemySoldiers; // An upper bound on the # of enemy soldiers
    static int numAlliedPastrs;
    static int numAlliedSoldiers;
    static int numAlliedNoiseTowers;
    static double ourMilk;
    static double theirMilk;

    static MapLocation rallyLoc = null;

    static boolean onePastrAttackModeTriggered = false;

    static boolean proxyPastrBuildTriggered = false;

    static MapLocation[] theirPastrs;
    static MapLocation[] ourPastrs;
    static RobotInfo[] allAllies;

    // Used for pastr placement
    static double[][] cowGrowth;
    static double[][] computedPastrScores = null;
    public static final int MAX_PASTR_LOCATIONS = 10;
    static MapLocation[] bestPastrLocations = new MapLocation[MAX_PASTR_LOCATIONS];
    static int numPastrLocations = 0;

    public static final int MAX_SUPPRESSORS = 1;
    static boolean singleSuppressorTriggered = false;
    static int numSuppressorTargets = 0;

    private static void turn() throws GameActionException {
        updateStrategicInfo();

        // First turn gets special treatment: spawn then do a bunch of computation
        // (which we devoutly hope will finished before the spawn timer is up
        // and before anyone attacks us).
        if (Clock.getRoundNum() == 0) {
            doFirstTurn();
            return;
        }

        if (rc.isActive()) attackEnemies();
        if (rc.isActive()) spawnSoldier();

        directStrategy();

        pathfindWithSpareBytecodes();
    }

    private static void doFirstTurn() throws GameActionException {
        spawnSoldier();

        computePastrScoresNonProxy();
        computeBestPastrLocations();

        Strategy.active = pickStrategyByAnalyzingMap();
        MessageBoard.STRATEGY.writeStrategy(Strategy.active);

        if (Strategy.active == Strategy.ONE_PASTR || Strategy.active == Strategy.ONE_PASTR_SUPPRESSOR || Strategy.active == Strategy.SCATTER
                || Strategy.active == Strategy.SCATTER_SUPPRESSOR) {
            broadcastBestPastrLocations();
        }
    }

    private static void broadcastBestPastrLocations() throws GameActionException {
        for (int i = 0; i < numPastrLocations; i++) {
            MessageBoard.BEST_PASTR_LOCATIONS.writeToMapLocationList(i, bestPastrLocations[i]);
        }
        MessageBoard.NUM_PASTR_LOCATIONS.writeInt(numPastrLocations);
    }

    private static Strategy pickStrategyByAnalyzingMap() throws GameActionException {
        // return Strategy.PROXY_ATTACK;
        // return Strategy.ONE_PASTR;
        // return Strategy.ONE_PASTR_SUPPRESSOR;
        // return Strategy.SCATTER;
        // return Strategy.SCATTER_SUPPRESSOR;
        return Strategy.RUSH;
    }

    private static void updateStrategicInfo() throws GameActionException {
        theirPastrs = rc.sensePastrLocations(them);
        numEnemyPastrs = theirPastrs.length;
        ourPastrs = rc.sensePastrLocations(us);
        numAlliedPastrs = ourPastrs.length;

        int numKillsLastTurn = MessageBoard.ROUND_KILL_COUNT.readInt();
        MessageBoard.ROUND_KILL_COUNT.writeInt(0);
        maxEnemySpawns -= numKillsLastTurn;

        virtualSpawnCountdown--;
        if (virtualSpawnCountdown <= 0) {
            maxEnemySpawns++;
            int maxEnemyPopCount = maxEnemySpawns + numEnemyPastrs;
            virtualSpawnCountdown = (int) Math.round(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1
                    + Math.pow(maxEnemyPopCount - 1, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
        }
        maxEnemySoldiers = maxEnemySpawns - numEnemyPastrs;

        numAlliedSoldiers = 0;
        Robot[] allAlliedRobots = rc.senseNearbyGameObjects(Robot.class, 999999, us);
        allAllies = new RobotInfo[allAlliedRobots.length];
        boolean[] towerBuildersAlive = new boolean[numPastrLocations];
        boolean[] pastrBuildersAlive = new boolean[numPastrLocations];
        boolean[] suppressorBuildersAlive = new boolean[numSuppressorTargets];
        for (int i = allAlliedRobots.length; i-- > 0;) {
            Robot ally = allAlliedRobots[i];
            RobotInfo info = rc.senseRobotInfo(ally);
            allAllies[i] = info;
            if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
            int id = ally.getID();
            for (int j = 0; j < numPastrLocations; j++) {
                if (id == MessageBoard.TOWER_BUILDER_ROBOT_IDS.readCurrentAssignedID(j)) towerBuildersAlive[j] = true;
                if (id == MessageBoard.PASTR_BUILDER_ROBOT_IDS.readCurrentAssignedID(j)) pastrBuildersAlive[j] = true;
            }
            for (int j = 0; j < numSuppressorTargets; j++) {
                if (id == MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.readCurrentAssignedID(j)) suppressorBuildersAlive[j] = true;
            }
        }

        for (int i = 0; i < numPastrLocations; i++) {
            if (!towerBuildersAlive[i]) MessageBoard.TOWER_BUILDER_ROBOT_IDS.clearAssignment(i);
            if (!pastrBuildersAlive[i]) MessageBoard.PASTR_BUILDER_ROBOT_IDS.clearAssignment(i);
        }
        for (int i = 0; i < numSuppressorTargets; i++) {
            if (!suppressorBuildersAlive[i]) MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.clearAssignment(i);
        }

        ourMilk = rc.senseTeamMilkQuantity(us);
        theirMilk = rc.senseTeamMilkQuantity(them);
    }

    private static void directStrategy() throws GameActionException {
        switch (Strategy.active) {
            case ONE_PASTR:
            case ONE_PASTR_SUPPRESSOR:
                directStrategyOnePastr();
                break;

            case PROXY:
            case PROXY_ATTACK:
                directStrategyProxy();
                break;

            case SCATTER:
            case SCATTER_SUPPRESSOR:
                directStrategyScatter();
                break;

            case RUSH:
                directStrategyRush();
                break;

            default:
                System.out.println("Uh oh! Unknown strategy!");
                break;
        }
    }

    private static void directStrategyProxy() throws GameActionException {
        boolean beAggressive = false;
        if (numEnemyPastrs == 0) {
            if (proxyPastrBuildTriggered) {
                rallyLoc = bestPastrLocations[0];
            } else {
                rallyLoc = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
                while (rc.senseTerrainTile(rallyLoc) == TerrainTile.VOID) {
                    rallyLoc = rallyLoc.add(rallyLoc.directionTo(ourHQ));
                }
            }
            beAggressive = true; // Need to punch through to our own pastr location
        } else if (numEnemyPastrs == 1) {
            rallyLoc = theirPastrs[0];
            beAggressive = Strategy.active == Strategy.PROXY_ATTACK; // Only try to destroy the enemy pastr if using PROXY_ATTACK
        } else {
            rallyLoc = chooseEnemyPastrAttackTarget();
            beAggressive = true; // They've overextended themselves, so try to destroy their pastrs
        }
        MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
        MessageBoard.BE_AGGRESSIVE.writeBoolean(beAggressive);

        // Tell individual soldiers when to construct noise tower and pastr
        if (!proxyPastrBuildTriggered && (numEnemyPastrs >= 1 || Clock.getRoundNum() > 1300)) {
            proxyPastrBuildTriggered = true;
            computePastrScoresProxy();
            computeBestPastrLocations();
            broadcastBestPastrLocations();
        }
    }

    private static void directStrategyScatter() throws GameActionException {
        if (Strategy.active == Strategy.SCATTER_SUPPRESSOR) {
            directSingleSuppressor();
        }

        rallyLoc = chooseEnemyPastrAttackTarget();
        boolean beAggressive = false;
        if (rallyLoc == null || rallyLoc.distanceSquaredTo(theirHQ) <= 5) {
            int bestDistSq = 999999;
            MapLocation soldierCenter = findSoldierCenterOfMass();
            if (soldierCenter == null) soldierCenter = ourHQ;
            for (int i = 0; i < numPastrLocations; i++) {
                MapLocation pastrLoc = bestPastrLocations[i];
                int distSq = soldierCenter.distanceSquaredTo(pastrLoc);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    rallyLoc = pastrLoc;
                    beAggressive = true; // need to punch through to our pastr
                }
            }
        }
        MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
        MessageBoard.BE_AGGRESSIVE.writeBoolean(beAggressive);
    }

    private static void directStrategyOnePastr() throws GameActionException {
        boolean desperation = false;

        if (Strategy.active == Strategy.ONE_PASTR_SUPPRESSOR) {
            directSingleSuppressor();
        }

        // Decided whether to trigger attack mode
        if (!onePastrAttackModeTriggered) {
            // if the enemy has overextended himself building pastrs, attack!
            // I think even building two pastrs is an overextension that we can punish.
            if (numEnemyPastrs >= 2) {
                // We should only try to punish them if we have at least as many soldiers as they do
                // One issue is that we may be overestimating their strength because with some probability
                // they've built a noise tower, sacrificing a soldier and slowing their spawns more than
                // we would estimate. So fudge it by 1 soldier:
                if (numAlliedSoldiers + 1 >= maxEnemySoldiers) {
                    // However, we need to make sure that at least one of these pastrs is outside their
                    // HQ zone. There's no point in trying to punish them for building a bunch of pastrs
                    // around their HQ; it doesn't help them and we can't kill them.
                    if (theyHavePastrOutsideHQ()) {
                        // If all these conditions are met, then punish them!
                        onePastrAttackModeTriggered = true;
                    }
                }
            }

            // if the enemy is out-milking us, then we need to attack or we are going to lose
            if (theirMilk >= 0.4 * GameConstants.WIN_QTY && theirMilk > ourMilk) {
                // If they just have a single pastr next to their HQ, though, attacking them won't do much
                // good. Better to just hope we out-milk them
                if (theyHavePastrOutsideHQ()) {
                    onePastrAttackModeTriggered = true;
                    desperation = true;
                }
            }
        }

        if (onePastrAttackModeTriggered) {
            rallyLoc = chooseEnemyPastrAttackTarget();

            // If we don't have a pastr, and we can't kill theirs, don't try to
            if (numAlliedPastrs == 0 && rallyLoc != null && rallyLoc.isAdjacentTo(theirHQ)) {
                rallyLoc = null;
            }

            // if there's no pastr to attack, decide whether to camp their spawn or to defend/rebuid our pastr:
            if (rallyLoc == null) {
                // first, if our pastr has been destroyed we need to rebuild it instead of attacking.
                // but if it's up, consider camping their spawn. Camping their spawn is only a good idea
                // if we are ahead on milk. Otherwise we should defend our pastr
                if (numAlliedPastrs > 0 && !desperation) {
                    rallyLoc = theirHQ;
                } else {
                    rallyLoc = bestPastrLocations[0];
                }
            }
        } else {
            // If not attacking, rally to our pastr
            rallyLoc = bestPastrLocations[0];
        }
        MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
    }

    private static void directStrategyRush() throws GameActionException {
        if (numEnemyPastrs == 0) {
            // rallyLoc = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            // while (rc.senseTerrainTile(rallyLoc) == TerrainTile.VOID) {
            // rallyLoc = rallyLoc.add(rallyLoc.directionTo(ourHQ));
            // }
            rallyLoc = theirHQ;
        } else {
            rallyLoc = chooseEnemyPastrAttackTarget();
        }
        MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
        MessageBoard.BE_AGGRESSIVE.writeBoolean(true);
    }

    private static void directSingleSuppressor() throws GameActionException {
        if (theirPastrs.length > 0 && !singleSuppressorTriggered) {
            numSuppressorTargets = 1;
            MessageBoard.NUM_SUPPRESSORS.writeInt(numSuppressorTargets);
            MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.writeToMapLocationList(0, theirPastrs[0]);
            singleSuppressorTriggered = true;
            Debug.indicate("suppressor", 0, "ordering suppressor for " + theirPastrs[0].toString());
        }

        if (theirPastrs.length == 0 && singleSuppressorTriggered) {
            numSuppressorTargets = 0;
            MessageBoard.NUM_SUPPRESSORS.writeInt(0);
            singleSuppressorTriggered = false;
        }

    }

    private static boolean theyHavePastrOutsideHQ() {
        for (int i = numEnemyPastrs; i-- > 0;) {
            if (!theirPastrs[i].isAdjacentTo(theirHQ)) { return true; }
        }
        return false;
    }

    private static MapLocation findSoldierCenterOfMass() {
        int x = 0;
        int y = 0;
        int N = 0;
        for (int i = allAllies.length; i-- > 0;) {
            RobotInfo ally = allAllies[i];
            if (ally.type == RobotType.SOLDIER && !ally.isConstructing) {
                MapLocation allyLoc = ally.location;
                x += allyLoc.x;
                y += allyLoc.y;
                N++;
            }
        }
        if (N == 0) return null;
        return new MapLocation(x / N, y / N);
    }

    private static MapLocation chooseEnemyPastrAttackTarget() {
        MapLocation soldierCenter = findSoldierCenterOfMass();

        if (soldierCenter == null) soldierCenter = ourHQ;

        int bestDifficulty = 999999;
        MapLocation bestTarget = null;
        for (int i = theirPastrs.length; i-- > 0;) {
            MapLocation pastr = theirPastrs[i];
            int difficulty = pastr.distanceSquaredTo(soldierCenter); // could be as high as 10000
            int distSqToTheirHQ = pastr.distanceSquaredTo(theirHQ);
            if (distSqToTheirHQ <= 5) difficulty += 10000 * distSqToTheirHQ; // save pastrs near HQ for last
            if (difficulty < bestDifficulty) {
                bestDifficulty = difficulty;
                bestTarget = pastr;
            }
        }
        return bestTarget;
    }

    // TODO: this takes a little too long on big maps
    private static void computePastrScoresNonProxy() {
        double mapSize = Math.hypot(mapWidth, mapHeight);
        MapLocation mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

        int spacing = mapWidth * mapHeight <= 2500 ? 3 : 5;

        double[][] pastrScores = new double[mapWidth][mapHeight];
        for (int y = 2; y < mapHeight - 2; y += spacing) {
            for (int x = 2; x < mapWidth - 2; x += spacing) {
                MapLocation loc = new MapLocation(x, y);
                if (rc.senseTerrainTile(loc) != TerrainTile.VOID && !loc.equals(ourHQ) && !loc.equals(theirHQ)) {
                    double distOurHQ = Math.sqrt(loc.distanceSquaredTo(ourHQ));
                    double distTheirHQ = Math.sqrt(loc.distanceSquaredTo(theirHQ));
                    if (distOurHQ < distTheirHQ) {
                        int numCows = 0;
                        for (int cowX = x - 2; cowX <= x + 2; cowX++) {
                            for (int cowY = y - 2; cowY <= y + 2; cowY++) {
                                if (rc.senseTerrainTile(new MapLocation(cowX, cowY)) != TerrainTile.VOID) {
                                    numCows += cowGrowth[cowX][cowY];
                                }
                            }
                        }
                        if (numCows >= 5) {
                            double distCenter = Math.sqrt(loc.distanceSquaredTo(mapCenter));
                            double score = numCows * (1 + (1.0 * distCenter - 0.5 * distOurHQ + 0.5 * distTheirHQ) / mapSize);
                            if (distCenter < 10) score *= 0.5; // the center is a very bad place!!
                            for (int i = 8; i-- > 0;) {
                                if (rc.senseTerrainTile(loc.add(Direction.values()[i])) == TerrainTile.VOID) score *= 0.95;
                            }
                            pastrScores[x][y] = score;
                        } else {
                            pastrScores[x][y] = -999999; // must be at least some cows
                        }
                    } else {
                        pastrScores[x][y] = -999999; // only make pastrs on squares closer to our HQ than theirs
                    }
                } else {
                    pastrScores[x][y] = -999999; // don't make pastrs on void squares
                }
                // System.out.print(pastrScores[x][y] < 0 ? "XX " : String.format("%02d ", (int) pastrScores[x][y]));
            }
            // System.out.println();
        }

        computedPastrScores = pastrScores;
    }

    // TODO: this takes a little too long on big maps
    private static void computePastrScoresProxy() {
        double mapSize = Math.hypot(mapWidth, mapHeight);

        boolean theyHavePastr = theirPastrs.length > 0;
        MapLocation theirPastr = null;
        if (theyHavePastr) theirPastr = theirPastrs[0];

        int spacing = mapWidth * mapHeight <= 2500 ? 3 : 5;

        double[][] pastrScores = new double[mapWidth][mapHeight];
        for (int y = 2; y < mapHeight - 2; y += spacing) {
            for (int x = 2; x < mapWidth - 2; x += spacing) {
                MapLocation loc = new MapLocation(x, y);
                if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID && !loc.equals(ourHQ) && !loc.equals(theirHQ)) {
                    double distOurHQ = Math.sqrt(loc.distanceSquaredTo(ourHQ));
                    double distTheirHQ = Math.sqrt(loc.distanceSquaredTo(theirHQ));
                    if (distOurHQ <= distTheirHQ) {
                        double distTheirPastr = theyHavePastr ? Math.sqrt(loc.distanceSquaredTo(theirPastr)) : 100;
                        if (distTheirPastr > 10) {
                            int numCows = 0;
                            for (int cowX = x - 2; cowX <= x + 2; cowX++) {
                                for (int cowY = y - 2; cowY <= y + 2; cowY++) {
                                    if (rc.senseTerrainTile(new MapLocation(cowX, cowY)) != TerrainTile.VOID) {
                                        numCows += cowGrowth[cowX][cowY];
                                    }
                                }
                            }
                            if (numCows >= 5) {
                                pastrScores[x][y] = numCows * (1 + 0.5 * distTheirPastr / mapSize);
                            } else {
                                pastrScores[x][y] = -999999; // must be at least some cows
                            }
                        } else {
                            pastrScores[x][y] = -999999;
                        }
                    } else {
                        pastrScores[x][y] = -999999; // only make pastrs on squares closer to our HQ than theirs
                    }
                } else {
                    pastrScores[x][y] = -999999; // don't make pastrs on void squares
                }
            }
        }

        computedPastrScores = pastrScores;
    }

    private static void computeBestPastrLocations() {
        MapLocation bestPastrLocation = null;
        double bestPastrScore = -999;

        int spacing = mapWidth * mapHeight <= 2500 ? 3 : 5;
        for (int y = 2; y < mapHeight - 2; y += spacing) {
            for (int x = 2; x < mapWidth - 2; x += spacing) {
                if (computedPastrScores[x][y] > bestPastrScore) {
                    MapLocation loc = new MapLocation(x, y);
                    if (!Util.contains(ourPastrs, new MapLocation(x, y))) {
                        bestPastrScore = computedPastrScores[x][y];
                        bestPastrLocation = loc;
                    }
                }
            }
        }

        bestPastrLocations[0] = bestPastrLocation;
        numPastrLocations = 1;
    }

    private static boolean attackEnemies() throws GameActionException {
        Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, ourHQ, 25, them);
        if (enemies.length == 0) return false;

        double bestTotalDamage = 0;
        MapLocation bestTarget = null;
        for (int i = enemies.length; i-- > 0;) {
            RobotInfo info = rc.senseRobotInfo(enemies[i]);
            MapLocation enemyLoc = info.location;
            if (!isInOurHQAttackRange(enemyLoc)) continue;

            int distSq = ourHQ.distanceSquaredTo(enemyLoc);
            MapLocation target = enemyLoc;
            double directDamage = RobotType.HQ.attackPower - RobotType.HQ.splashPower;
            if (distSq > RobotType.HQ.attackRadiusMaxSquared) {
                target = target.add(target.directionTo(ourHQ));
                directDamage = 0;
            }

            int enemiesSplashed = rc.senseNearbyGameObjects(Robot.class, target, 2, them).length;
            int alliesSplashed = rc.senseNearbyGameObjects(Robot.class, target, 2, us).length;
            double netSplashDamage = RobotType.HQ.splashPower * (enemiesSplashed - alliesSplashed);
            double totalDamage = directDamage + netSplashDamage;
            if (totalDamage > bestTotalDamage) {
                bestTotalDamage = totalDamage;
                bestTarget = target;
            }
        }

        if (bestTarget != null) rc.attackSquare(bestTarget);
        return true;
    }

    private static boolean spawnSoldier() throws GameActionException {
        if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;
        // if (rc.senseRobotCount() >= 2) return false;

        int spawnCount = MessageBoard.SPAWN_COUNT.readInt();

        Direction startDir = ourHQ.directionTo(theirHQ);
        if (startDir.isDiagonal()) startDir = startDir.rotateRight();

        // We prefer to spawn on orthogonal directions; this is slightly better for the hq pastr strat
        int[] offsets = new int[] { 0, 2, 4, 6, 1, 3, 5, 7 };
        for (int i = 0; i < offsets.length; i++) {
            Direction dir = Direction.values()[(startDir.ordinal() + offsets[i]) % 8];
            if (rc.canMove(dir)) {
                rc.spawn(dir);
                MessageBoard.SPAWN_COUNT.writeInt(spawnCount + 1);
                return true;
            }
        }

        return false;
    }

    private static void pathfindWithSpareBytecodes() throws GameActionException {
        int bytecodeLimit = 9000;

        if (Clock.getBytecodeNum() > bytecodeLimit) return;
        if (rallyLoc != null) Bfs.work(rallyLoc, Bfs.PRIORITY_HIGH, bytecodeLimit);

        for (int i = 0; i < numPastrLocations; i++) {
            if (Clock.getBytecodeNum() > bytecodeLimit) return;
            Bfs.work(bestPastrLocations[i], Bfs.PRIORITY_HIGH, bytecodeLimit);
        }

        // TODO: consider ordering this in a smarter way
        for (int i = 0; i < theirPastrs.length; i++) {
            if (Clock.getBytecodeNum() > bytecodeLimit) return;
            Bfs.work(theirPastrs[i], Bfs.PRIORITY_LOW, bytecodeLimit);
        }
    }

}
