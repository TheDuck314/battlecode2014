package anatid17_scatter;

import anatid.BotSoldier.MicroStance;
import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
		Debug.init(rc, "pages");

		cowGrowth = rc.senseCowGrowth();
	}

	// Strategic info
	int virtualSpawnCountdown = 0;
	int maxEnemySpawns;
	int numEnemyPastrs;
	int maxEnemySoldiers; // An upper bound on the # of enemy soldiers
	int numAlliedPastrs;
	int numAlliedSoldiers;
	int numAlliedNoiseTowers;
	double ourMilk;
	double theirMilk;

	MapLocation[] theirPastrs;
	MapLocation[] ourPastrs;
	RobotInfo[] allAllies;

	MapLocation rallyLoc = null;

	// Used for pastr placement
	double[][] cowGrowth;
	double[][] computedPastrScores = null;
	static final int MAX_PASTR_LOCATIONS = 10;
	MapLocation[] bestPastrLocations = new MapLocation[MAX_PASTR_LOCATIONS];
	int numPastrLocations = 0;

	public void turn() throws GameActionException {
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

	private void doFirstTurn() throws GameActionException {
		MessageBoard.setDefaultChannelValues();
		spawnSoldier();

		computePastrScores();
		computeBestPastrLocations();

		MessageBoard.NUM_PASTR_LOCATIONS.writeInt(numPastrLocations);
		for (int i = 0; i < numPastrLocations; i++) {
			MessageBoard.BEST_PASTR_LOCATIONS.writeToMapLocationList(i, bestPastrLocations[i]);
		}
	}

	private void updateStrategicInfo() throws GameActionException {
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
		for (int i = allAlliedRobots.length; i-- > 0;) {
			Robot ally = allAlliedRobots[i];
			RobotInfo info = rc.senseRobotInfo(ally);
			allAllies[i] = info;
			if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
			int id = ally.getID();
			for (int j = 0; j < numPastrLocations; j++) {
				if (id == MessageBoard.TOWER_BUILDER_ROBOT_IDS.readInt()) towerBuildersAlive[j] = true;
				if (id == MessageBoard.PASTR_BUILDER_ROBOT_IDS.readInt()) pastrBuildersAlive[j] = true;
			}
		}

		for (int i = 0; i < numPastrLocations; i++) {
			if (!towerBuildersAlive[i]) MessageBoard.TOWER_BUILDER_ROBOT_IDS.clearAssignment(i);
			if (!pastrBuildersAlive[i]) MessageBoard.PASTR_BUILDER_ROBOT_IDS.clearAssignment(i);
		}

		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}

	private void directStrategy() throws GameActionException {
		directStrategyShotgun();
	}

	private void directStrategyShotgun() throws GameActionException {
		rallyLoc = chooseEnemyPastrAttackTarget();
		if (rallyLoc == null) {
			rallyLoc = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
			while (rc.senseTerrainTile(rallyLoc) == TerrainTile.VOID) {
				rallyLoc = rallyLoc.add(rallyLoc.directionTo(ourHQ));
			}
		}
		MessageBoard.RALLY_LOC.writeMapLocation(rallyLoc);
		MessageBoard.BE_AGGRESSIVE.writeBoolean(false);
	}

	private MapLocation findSoldierCenterOfMass() {
		int x = 0;
		int y = 0;
		int N = 0;
		for (int i = allAllies.length; i-- > 0;) {
			RobotInfo ally = allAllies[i];
			if (ally.type == RobotType.SOLDIER) {
				MapLocation allyLoc = ally.location;
				x += allyLoc.x;
				y += allyLoc.y;
				N++;
			}
		}
		if (N == 0) return null;
		return new MapLocation(x / N, y / N);
	}

	private MapLocation chooseEnemyPastrAttackTarget() {
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
	private void computePastrScores() {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		double mapSize = Math.hypot(mapWidth, mapHeight);
		MapLocation mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

		double[][] pastrScores = new double[mapWidth][mapHeight];
		for (int y = 2; y < mapHeight - 2; y += 5) {
			for (int x = 2; x < mapWidth - 2; x += 5) {
				if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID) {
					MapLocation loc = new MapLocation(x, y);
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
							pastrScores[x][y] = numCows * (1 + (1.0 * distCenter - 0.5 * distOurHQ + 0.5 * distTheirHQ) / mapSize);
						} else {
							pastrScores[x][y] = -999999; // must be at least some cows
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

	private void computeBestPastrLocations() {

		MapLocation bestPastrLocation = null;
		double bestPastrScore = -999;

		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		for (int x = 2; x < mapWidth - 2; x += 5) {
			for (int y = 2; y < mapHeight - 2; y += 5) {
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

	private boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, here, 25, them);
		if (enemies.length == 0) return false;

		double bestTotalDamage = 0;
		MapLocation bestTarget = null;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(enemies[i]);
			MapLocation enemyLoc = info.location;
			if (!Util.inHQAttackRange(enemyLoc, here)) continue;

			int distSq = here.distanceSquaredTo(enemyLoc);
			MapLocation target = enemyLoc;
			double directDamage = RobotType.HQ.attackPower - RobotType.HQ.splashPower;
			if (distSq > RobotType.HQ.attackRadiusMaxSquared) {
				target = target.add(target.directionTo(here));
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

	private boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;
		// if (rc.senseRobotCount() >= 2) return false;

		int spawnCount = MessageBoard.SPAWN_COUNT.readInt();

		Direction startDir = here.directionTo(theirHQ);
		if (startDir.isDiagonal()) startDir = startDir.rotateRight();

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

	private void pathfindWithSpareBytecodes() throws GameActionException {
		MapLocation pathingDest;
		int bytecodeLimit = 9000;

		for (int i = 0; i < numPastrLocations; i++) {
			if (Clock.getBytecodeNum() > bytecodeLimit) return;
			Bfs.work(bestPastrLocations[i], Bfs.PRIORITY_HIGH, bytecodeLimit);
		}

		if (Clock.getBytecodeNum() > bytecodeLimit) return;
		if (rallyLoc != null) Bfs.work(rallyLoc, Bfs.PRIORITY_HIGH, bytecodeLimit);

		// TODO: consider ordering this in a smarter way
		for (int i = 0; i < theirPastrs.length; i++) {
			if (Clock.getBytecodeNum() > bytecodeLimit) return;
			Bfs.work(theirPastrs[i], Bfs.PRIORITY_LOW, bytecodeLimit);
		}
	}
}
