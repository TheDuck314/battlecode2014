package framework;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
		Debug.init(rc, "splash");

		hqSeparation = Math.sqrt(ourHQ.distanceSquaredTo(theirHQ));
		cowGrowth = rc.senseCowGrowth();
	}

	// Strategic info
	double hqSeparation;
	int virtualSpawnCountdown = 0;
	int maxEnemySpawns;
	int numEnemyPastrs;
	int maxEnemySoldiers; // An upper bound on the # of enemy soldiers
	int numAlliedPastrs;
	int numAlliedSoldiers;
	int numAlliedNoiseTowers;
	double ourMilk;
	double theirMilk;

	boolean attackModeTriggered = false;
	MapLocation attackModeTarget = null;

	MapLocation[] theirPastrs;
	MapLocation[] ourPastrs;
	RobotInfo[] allAllies;

	// Used for pastr placement
	double[][] cowGrowth;
	double[][] computedPastrScores = null;
	MapLocation computedBestPastrLocation = null;

	public void turn() throws GameActionException {
		updateStrategicInfo();

		// First turn gets special treatment: spawn then do a bunch of computation
		// (which we devoutly hope will finished before the spawn timer is up
		// and before anyone attacks us).
		if (Clock.getRoundNum() == 0) {
			doFirstTurn();
			return;
		}

		if (rc.isActive()) {
			spawnSoldier();
		}

		attackEnemies();

		directStrategy();

		// Use spare bytecodes to do pathing computations
		MapLocation pathingDest;
		if (attackModeTarget != null) pathingDest = attackModeTarget;
		else if (computedBestPastrLocation != null) pathingDest = computedBestPastrLocation;
		else pathingDest = null;

		if (pathingDest != null) Bfs.work(pathingDest, rc, 9000);
	}

	private void doFirstTurn() throws GameActionException {
		MessageBoard.setDefaultChannelValues();
		spawnSoldier();

		computePastrScores();
		computeBestPastrLocation();
		MessageBoard.BEST_PASTR_LOC.writeMapLocation(computedBestPastrLocation);
	}

	// Guess how many bots the opponent has
	// TODO: account for pop cap
	// TODO: record kills of enemy units
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
		for (int i = allAlliedRobots.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(allAlliedRobots[i]);
			allAllies[i] = info;
			if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
		}

		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}

	private void directStrategy() throws GameActionException {
		switch (Strategy.active) {
			case NOISE_THEN_ONE_PASTR:
			case ONE_PASTR_THEN_NOISE:
				directStrategyOnePastr();
				break;

			case HQ_PASTR:
			case RUSH:
				directStrategyRush();
				break;

			case MACRO:
				break;

			default:
				System.out.println("Uh oh! Unknown strategy!");
				break;
		}
	}

	private void directStrategyRush() throws GameActionException {
		attackModeTarget = chooseEnemyPastrAttackTarget();
		if (attackModeTarget == null) { // rally toward center if there are no enemy pastrs
			attackModeTarget = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
			while (rc.senseTerrainTile(attackModeTarget) == TerrainTile.VOID) {
				attackModeTarget = attackModeTarget.add(attackModeTarget.directionTo(ourHQ));
			}
		}
		MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget);
	}

	private void directStrategyOnePastr() throws GameActionException {
		// Decided whether to trigger attack mode
		if (!attackModeTriggered) {
			if (numEnemyPastrs >= 2) {
				// if the enemy has overextended himself building pastrs, attack!
				// The threshold to attack should be smaller on a smaller map because
				// it will take less time to get there, so the opponent will have less time to
				// mend his weakness.
				int attackThreshold = 1 + (int) (hqSeparation / 40);
				if (numAlliedSoldiers - maxEnemySoldiers >= attackThreshold) {
					attackModeTriggered = true;
				}
			}

			// if the enemy is out-milking us, then we need to attack or we are going to lose
			if (theirMilk >= 0.4 * GameConstants.WIN_QTY && theirMilk > ourMilk) {
				attackModeTriggered = true;
			}
		}

		if (attackModeTriggered) {
			attackModeTarget = chooseEnemyPastrAttackTarget();
			if (attackModeTarget == null && ourPastrs.length > 0) attackModeTarget = theirHQ; // if they have no pastrs, camp their spawn
			MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget);
		}
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
		return new MapLocation(x / N, y / N);
	}

	private MapLocation chooseEnemyPastrAttackTarget() {
		MapLocation soldierCenter = findSoldierCenterOfMass();

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

	// TODO: this takes a little too long on
	private void computePastrScores() {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();

		double[][] pastrScores = new double[mapWidth][mapHeight];
		for (int x = 2; x < mapWidth - 2; x += 5) {
			for (int y = 2; y < mapHeight - 2; y += 5) {
				if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID) {
					MapLocation loc = new MapLocation(x, y);
					double distDiff = Math.sqrt(loc.distanceSquaredTo(theirHQ)) - Math.sqrt(loc.distanceSquaredTo(ourHQ));
					if (distDiff > 10) {
						pastrScores[x][y] = distDiff * 0.5;
						for (int cowX = x - 2; cowX <= x + 2; cowX++) {
							for (int cowY = y - 2; cowY <= y + 2; cowY++) {
								if (rc.senseTerrainTile(new MapLocation(cowX, cowY)) != TerrainTile.VOID) {
									pastrScores[x][y] += cowGrowth[cowX][cowY];
								}
							}
						}
					} else {
						pastrScores[x][y] -= 999999; // only make pastrs on squares closer to our HQ than theirs
					}
				} else {
					pastrScores[x][y] -= 999999; // don't make pastrs on void squares
				}
			}
		}

		computedPastrScores = pastrScores;
	}

	private void computeBestPastrLocation() {
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

		computedBestPastrLocation = bestPastrLocation;
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

		Direction dir = here.directionTo(theirHQ);
		for (int i = 8; i-- > 0;) {
			if (rc.canMove(dir)) {
				// Don't put base noise tower or pastr on diagonals, or we might hit them with splash when we kill soldiers that try to attack them.
				if (!(spawnCount <= 1 && dir.isDiagonal())) {
					rc.spawn(dir);
					MessageBoard.SPAWN_COUNT.writeInt(spawnCount + 1);
					return true;
				}
			}
			dir = dir.rotateRight();
		}

		// Try diagonals if necessary
		if (spawnCount <= 1) {
			for (int i = 8; i-- > 0;) {
				if (rc.canMove(dir)) {
					rc.spawn(dir);
					MessageBoard.SPAWN_COUNT.writeInt(spawnCount + 1);
					return true;
				}
				dir = dir.rotateRight();
			}
		}

		return false;
	}
}
