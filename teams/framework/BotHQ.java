package framework;

import java.util.ArrayList;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
		Debug.init(rc, "bfs");

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

		directStrategyRush();

		// Use spare bytecodes to do pathing computations
		MapLocation pathingDest;
		if (attackModeTarget != null) pathingDest = attackModeTarget;
		else if (computedBestPastrLocation != null) pathingDest = computedBestPastrLocation;
		else pathingDest = null;

		if (pathingDest != null) Bfs.work(pathingDest, rc, 9000);
	}

	private void doFirstTurn() throws GameActionException {
		initMessageBoard();
		spawnSoldier();

		computePastrScores();
		computeBestPastrLocation();
		MessageBoard.BEST_PASTR_LOC.writeMapLocation(computedBestPastrLocation, rc);
	}

	private void initMessageBoard() throws GameActionException {
		MessageBoard.BEST_PASTR_LOC.writeMapLocation(null, rc);
		MessageBoard.ATTACK_LOC.writeMapLocation(null, rc);
		MessageBoard.BUILDING_NOISE_TOWER.writeMapLocation(null, rc);
	}

	// Guess how many bots the opponent has
	// TODO: account for pop cap
	// TODO: record kills of enemy units
	private void updateStrategicInfo() throws GameActionException {
		theirPastrs = rc.sensePastrLocations(them);
		numEnemyPastrs = theirPastrs.length;
		ourPastrs = rc.sensePastrLocations(us);
		numAlliedPastrs = ourPastrs.length;

		virtualSpawnCountdown--;
		if (virtualSpawnCountdown <= 0) {
			maxEnemySpawns++;
			int maxEnemyPopCount = maxEnemySpawns + numEnemyPastrs;
			virtualSpawnCountdown = (int) Math.round(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1
					+ Math.pow(maxEnemyPopCount - 1, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
		}
		maxEnemySoldiers = maxEnemySpawns - numEnemyPastrs;

		numAlliedSoldiers = 0;
		Robot[] alliedUnits = rc.senseNearbyGameObjects(Robot.class, 999999, us);
		for (int i = alliedUnits.length; i-- > 0;) {
			Robot unit = alliedUnits[i];
			RobotInfo info = rc.senseRobotInfo(unit);
			if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
		}

		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}
	
	private void directStrategyRush() throws GameActionException {
		attackModeTarget = Util.closest(theirPastrs, ourHQ);
		if (attackModeTarget == null) attackModeTarget = new MapLocation(rc.getMapWidth()/2, rc.getMapHeight()/2);
		MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget, rc);
	}

	private void directStrategyMacro() throws GameActionException {		
		// Decided whether to trigger attack mode
		if (!attackModeTriggered) {
			// if the enemy has overextended himself building pastrs, attack!
			// The threshold to attack should be smaller on a smaller map because
			// it will take less time to get there, so the opponent will have less time to
			// mend his weakness.
			if (numEnemyPastrs >= 2) {
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
			attackModeTarget = Util.closest(theirPastrs, ourHQ);
			MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget, rc);
		}
	}

	// TODO: this takes a little too long on
	private void computePastrScores() {
		Util.timerStart();
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
		Util.timerEnd("computePastrScores");

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
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.HQ.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	private boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;
		// if (rc.senseRobotCount() >= 1) return false;

		Direction dir = Util.opposite(ourHQ.directionTo(theirHQ)).rotateLeft();
		for (int i = 8; i-- > 0;) {
			if (rc.canMove(dir)) {
				rc.spawn(dir);
				return true;
			} else {
				dir = dir.rotateRight();
			}
		}

		return false;
	}
}
