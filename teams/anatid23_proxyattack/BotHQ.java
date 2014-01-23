package anatid23_proxyattack;

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

		// Precompute an obstacle-free central rally point
		centralRallyPoint = new MapLocation(mapWidth / 2, mapHeight / 2);
		while (rc.senseTerrainTile(centralRallyPoint) == TerrainTile.VOID) {
			centralRallyPoint = centralRallyPoint.add(centralRallyPoint.directionTo(ourHQ));
		}
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

	static MapLocation centralRallyPoint;

	// Properly choosing what to do in micro requires an understand of what the current goal is.
	public enum RallyGoal {
		DESTROY, // We are trying to attack and destroy an enemy pastr at the rally point.
		HARRASS, // We are trying to encircle an enemy pastr and kill and scare away cows, without risking our own bots
		DEFEND, // We are trying to defend an allied pastr at the rally point.
		GATHER // We are trying to gather our forces together at the rally point, without risking our own bots
	}

	static MapLocation rallyLoc = null;
	static RallyGoal rallyGoal = RallyGoal.GATHER;

	static boolean attackModeTriggered = false;

	static boolean proxyPastrBuildTriggered = false;

	static int rushBuildStartRound = 0;
	static boolean rushBuildOrderIssued = false;
	static MapLocation rushPastrRepel = null;
	static boolean rushPastrSafe = false;

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

		MapLocation repel = null;
		boolean safe = true;
		computePastrScores(repel, safe);
		computeOneGoodPastrLocation();

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
		return Strategy.PROXY_ATTACK;
		// return Strategy.PROXY;
		// return Strategy.ONE_PASTR;
		// return Strategy.ONE_PASTR_SUPPRESSOR;
		// return Strategy.SCATTER;
		// return Strategy.SCATTER_SUPPRESSOR;
		// return Strategy.RUSH;
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
		if(numAlliedPastrs > 0) MessageBoard.BUILD_PASTRS_FAST.writeBoolean(true);
		
		switch (Strategy.active) {
			case ONE_PASTR:
			case ONE_PASTR_SUPPRESSOR:
			case SCATTER:
			case SCATTER_SUPPRESSOR:
				directStrategyOnePastr();
				break;

			case PROXY:
			case PROXY_ATTACK:
				directStrategyProxy();
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
		if (numEnemyPastrs == 0) {
			if (proxyPastrBuildTriggered) {
				rallyLoc = bestPastrLocations[0];
				rallyGoal = RallyGoal.DEFEND;
			} else {
				rallyLoc = centralRallyPoint;
				rallyGoal = RallyGoal.GATHER;
			}
		} else if (numEnemyPastrs == 1) {
			rallyLoc = theirPastrs[0];
			if (Strategy.active == Strategy.PROXY_ATTACK) rallyGoal = RallyGoal.DESTROY;
			else rallyGoal = RallyGoal.HARRASS;
		} else {
			rallyLoc = chooseEnemyPastrAttackTarget();
			rallyGoal = RallyGoal.DESTROY;
		}
		MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
		MessageBoard.RALLY_GOAL.writeRallyGoal(rallyGoal);

		// Tell individual soldiers when to construct noise tower and pastr
		if (!proxyPastrBuildTriggered && (numEnemyPastrs >= 1 || Clock.getRoundNum() > 1300)) {
			proxyPastrBuildTriggered = true;
			MapLocation repel = numEnemyPastrs > 0 ? theirPastrs[0] : null;
			boolean safe = false;
			computePastrScores(repel, safe);
			computeOneGoodPastrLocation();
			broadcastBestPastrLocations();
		}
	}

	private static void directStrategyOnePastr() throws GameActionException {
		if (Strategy.active == Strategy.ONE_PASTR_SUPPRESSOR || Strategy.active == Strategy.SCATTER_SUPPRESSOR) {
			directSingleSuppressor();
		}

		// Decided whether to trigger attack mode
		if (!attackModeTriggered) {
			// if the enemy has overextended himself building pastrs, attack!
			// I think even building two pastrs is an overextension that we can punish.
			if (numEnemyPastrs >= 2) {
				// However, we need to make sure that at least one of these pastrs is outside their
				// HQ zone. There's no point in trying to punish them for building a bunch of pastrs
				// around their HQ; it doesn't help them and we can't kill them.
				if (theyHavePastrOutsideHQ()) {
					// If all these conditions are met, then punish them!
					attackModeTriggered = true;
				}
			}

			// if the enemy is out-milking us, then we need to attack or we are going to lose
			if (theirMilk >= 0.4 * GameConstants.WIN_QTY && theirMilk > ourMilk) {
				// If they just have a single pastr next to their HQ, though, attacking them won't do much
				// good. Better to just hope we out-milk them
				if (theyHavePastrOutsideHQ()) {
					attackModeTriggered = true;
				}
			}
		}

		rallyLoc = null;
		if (attackModeTriggered) {
			// attack an enemy pastr
			rallyLoc = chooseEnemyPastrAttackTarget();
			rallyGoal = RallyGoal.DESTROY;

			// If we don't have a pastr, and we can't kill theirs, don't try to
			if (numAlliedPastrs == 0 && rallyLoc != null && rallyLoc.isAdjacentTo(theirHQ)) {
				rallyLoc = null;
			}
		}

		if (rallyLoc == null) { // if attack mode isn't triggered, or if there is no good target to attack
			rallyLoc = null;
			if (Strategy.active == Strategy.SCATTER || Strategy.active == Strategy.SCATTER_SUPPRESSOR) {
				// scatter strategies harrass the enemy pastrs, if any
				rallyLoc = chooseEnemyPastrAttackTarget();
				rallyGoal = RallyGoal.HARRASS;
			}
			// If we aren't harrassing, rally to the nearest allied pastr location
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
						rallyGoal = RallyGoal.DEFEND;
					}
				}
			}
		}
		MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
		MessageBoard.RALLY_GOAL.writeRallyGoal(rallyGoal);
	}

	private static void directStrategyRush() throws GameActionException {
		if (numEnemyPastrs > 0 && theyHavePastrOutsideHQ()) {
			// If they build a pastr outside their HQ, attack it!
			rallyLoc = chooseEnemyPastrAttackTarget();
			rallyGoal = RallyGoal.DESTROY;
		} else {
			// Otherwise, rally to the center (if we aren't building yet) or to our pastr
			if (!rushBuildOrderIssued) {
				rallyLoc = centralRallyPoint;
				// rallyLoc = theirHQ;
				rallyGoal = RallyGoal.GATHER;
			} else {
				rallyLoc = bestPastrLocations[0];
				rallyGoal = RallyGoal.DEFEND;
			}
		}
		MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
		MessageBoard.RALLY_GOAL.writeRallyGoal(rallyGoal);

		// decide whether to start building
		if (rushBuildStartRound == 0) {
			if (numEnemyPastrs > 0) {
				if (!theyHavePastrOutsideHQ()) {
					// Start building immediately if they build an HQ pastr
					rushBuildStartRound = Clock.getRoundNum();
				} else {
					// Otherwise start building a little while after they build their first pastr
					// The delay gives us time to send a couple extra new spawns into the fight, which
					// can make the difference
					rushBuildStartRound = Clock.getRoundNum() + 100;
				}
				rushPastrRepel = theirPastrs[0];
				rushPastrSafe = false;
			} else if (Clock.getRoundNum() >= 1000) {
				// If enough time passes without anything happening, build a pastr
				rushBuildStartRound = Clock.getRoundNum();
				rushPastrRepel = null;
				rushPastrSafe = true;
			}
		}

		// If we successfully destroy their pastr, eliminate the delay before building
		if (rushBuildStartRound > Clock.getRoundNum() && numEnemyPastrs == 0) {
			rushBuildStartRound = Clock.getRoundNum();
		}

		if (rushBuildStartRound > 0 && Clock.getRoundNum() >= rushBuildStartRound && !rushBuildOrderIssued) {
			computePastrScores(rushPastrRepel, rushPastrSafe);
			computeOneGoodPastrLocation();
			broadcastBestPastrLocations();
			rushBuildOrderIssued = true;
		}
	}

	private static void directSingleSuppressor() throws GameActionException {
		if (theirPastrs.length > 0 && !singleSuppressorTriggered) {
			numSuppressorTargets = 1;
			MessageBoard.NUM_SUPPRESSORS.writeInt(numSuppressorTargets);
			MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.writeToMapLocationList(0, theirPastrs[0]);
			singleSuppressorTriggered = true;
		}

		if (theirPastrs.length == 0 && singleSuppressorTriggered) {
			numSuppressorTargets = 0;
			MessageBoard.NUM_SUPPRESSORS.writeInt(0);
			singleSuppressorTriggered = false;
		}

	}

	private static boolean theyHavePastrOutsideHQ() {
		for (int i = numEnemyPastrs; i-- > 0;) {
			if (!theirPastrs[i].isAdjacentTo(theirHQ)) {
				return true;
			}
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

	private static void computePastrScores(MapLocation repel, boolean safe) {
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
							double score = numCows;
							if (safe) {
								// If we are playing it safe, avoid the center and try to keep near our HQ and away from theirs
								score *= (1 + (1.0 * distCenter - 0.5 * distOurHQ + 0.5 * distTheirHQ) / mapSize);
								if (distCenter < 10) score *= 0.5; // the center is a very bad place!!
							}
							if (repel != null && loc.distanceSquaredTo(repel) < 200) {
								// Optionally we can try to keep away from a given location
								score *= 0.5;
							}
							for (int i = 8; i-- > 0;) {
								// Bad idea to have pastrs next to walls. Enemies can shoot over them in a way that's hard to defend against
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

	private static void computeOneGoodPastrLocation() {
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

	private static void computeSecondGoodPastrLocation() {
		MapLocation bestSecondPastrLocation = null;
		double bestScore = -999;
		MapLocation firstPastrLoc = bestPastrLocations[0];

		int spacing = mapWidth * mapHeight <= 2500 ? 3 : 5;
		for (int y = 2; y < mapHeight - 2; y += spacing) {
			for (int x = 2; x < mapWidth - 2; x += spacing) {
				if (computedPastrScores[x][y] > bestScore) {
					MapLocation loc = new MapLocation(x, y);
					if (loc.distanceSquaredTo(firstPastrLoc) > 1600) {
						if (!Util.contains(ourPastrs, new MapLocation(x, y))) {
							bestScore = computedPastrScores[x][y];
							bestSecondPastrLocation = loc;
						}
					}
				}
			}
		}

		if (bestSecondPastrLocation != null) {
			bestPastrLocations[1] = bestSecondPastrLocation;
			numPastrLocations = 2;
		}
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
